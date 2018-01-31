package edu.stanford.futuredata.macrobase.analysis.summary.aplinear;

import edu.stanford.futuredata.macrobase.analysis.summary.util.AttributeEncoder;
import edu.stanford.futuredata.macrobase.analysis.summary.util.FastFixedHashSet;
import edu.stanford.futuredata.macrobase.analysis.summary.util.FastFixedHashTable;
import edu.stanford.futuredata.macrobase.analysis.summary.util.qualitymetrics.QualityMetric;
import edu.stanford.futuredata.macrobase.util.MacrobaseInternalError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Class for handling the generic, algorithmic aspects of apriori explanation.
 * This class assumes that subgroups posses "aggregates" such as count and outlier_count
 * which can be combined additively. Then, we use APriori to find the subgroups which
 * are the most interesting as defined by "quality metrics" on these aggregates.
 */
public class APrioriLinear {
    private Logger log = LoggerFactory.getLogger("APLSummarizer");

    // **Parameters**
    private QualityMetric[] qualityMetrics;
    private double[] thresholds;

    // **Cached values**

    // Singleton viable sets for quick lookup
    private LongOpenHashSet singleNext;
    private boolean[] singleNextArray;
    // Sets that has high enough support but not high risk ratio, need to be explored
    private HashMap<Integer, LongOpenHashSet> setNext;
    // Aggregate values for all of the sets we saved
    private HashMap<Integer, Long2ObjectOpenHashMap<double []>> savedAggregates;

    public APrioriLinear(
            List<QualityMetric> qualityMetrics,
            List<Double> thresholds
    ) {
        this.qualityMetrics = qualityMetrics.toArray(new QualityMetric[0]);
        this.thresholds = new double[thresholds.size()];
        for (int i = 0; i < thresholds.size(); i++) {
            this.thresholds[i] = thresholds.get(i);
        }
        this.setNext = new HashMap<>(3);
        this.savedAggregates = new HashMap<>(3);
    }

    public List<APLExplanationResult> explain(
            final int[][] attributes,
            double[][] aggregateColumns,
            int cardinality,
            HashMap<Integer, Integer> columnDecoder
    ) {
        // Number of threads in the parallelized sections of this function
        final int numThreads = 1;//Runtime.getRuntime().availableProcessors();
        final int numAggregates = aggregateColumns.length;
        final int numRows = aggregateColumns[0].length;
        final int numColumns = attributes[0].length;
        // The smallest integer x such that 2**x > cardinality
        final long logCardinality = 21;

        // Shard the dataset by rows for the threads, but store it by column for fast processing
        final int[][][] byThreadAttributesTranspose = new int[numThreads][numColumns][(numRows + numThreads)/numThreads];
        for (int threadNum = 0; threadNum < numThreads; threadNum++) {
            final int startIndex = (numRows * threadNum) / numThreads;
            final int endIndex = (numRows * (threadNum + 1)) / numThreads;
            for(int i = 0; i < numColumns; i++)
                for(int j = startIndex; j < endIndex; j++) {
                    byThreadAttributesTranspose[threadNum][i][j - startIndex] = attributes[j][i];
                }
        }

        // Quality metrics are initialized with global aggregates to
        // allow them to determine the appropriate relative thresholds
        double[] globalAggregates = new double[numAggregates];
        for (int j = 0; j < numAggregates; j++) {
            globalAggregates[j] = 0;
            double[] curColumn = aggregateColumns[j];
            for (int i = 0; i < numRows; i++) {
                globalAggregates[j] += curColumn[i];
            }
        }
        for (QualityMetric q : qualityMetrics) {
            q.initialize(globalAggregates);
        }

        // Row store for more convenient access
        final double[][] aRows = new double[numRows][numAggregates];
        for (int i = 0; i < numRows; i++) {
            for (int j = 0; j < numAggregates; j++) {
                aRows[i][j] = aggregateColumns[j][i];
            }
        }
        for (int curOrder = 1; curOrder <= 3; curOrder++) {
            long startTime = System.currentTimeMillis();
            // For order 3, pre-calculate all possible candidate sets from "next" sets of
            // previous orders. We will focus on aggregating results for these sets.
            final FastFixedHashSet precalculatedCandidates;
            if (curOrder == 3) {
                precalculatedCandidates = precalculateCandidates(columnDecoder, setNext.get(2),
                        singleNext, logCardinality);
            } else {
                precalculatedCandidates = null;
            }
            // Run the critical path of the algorithm--candidate generation--in parallel.
            final int curOrderFinal = curOrder;
            // The per-thread hashmaps.  These store less-common values.
            final ArrayList<FastFixedHashTable> threadSetAggregates = new ArrayList<>(numThreads);
            for (int i = 0; i < numThreads; i++) {
                threadSetAggregates.add(new FastFixedHashTable(100000, numAggregates));
            }
            final CountDownLatch doneSignal = new CountDownLatch(numThreads);
            // Shard the dataset by row into threads.
            for (int threadNum = 0; threadNum < numThreads; threadNum++) {
                final int curThreadNum = threadNum;
                final int startIndex = (numRows * threadNum) / numThreads;
                final int endIndex = (numRows * (threadNum + 1)) / numThreads;
                final FastFixedHashTable thisThreadSetAggregates = threadSetAggregates.get(threadNum);
                // Do the critical path calculation in a lambda
                Runnable APrioriLinearRunnable = () -> {
                    if (curOrderFinal == 1) {
                        for (int colNum = curThreadNum; colNum < numColumns + curThreadNum; colNum++) {
                            int[] curColumnAttributes = byThreadAttributesTranspose[curThreadNum][colNum % numColumns];
                            // Aggregate candidates.  This loop uses a hybrid system where common candidates are
                            // perfect-hashed for speed while less common ones are stored in a hash table.
                            for (int rowNum = startIndex; rowNum < endIndex; rowNum++) {
                                long curCandidate = curColumnAttributes[rowNum - startIndex];
                                if (curCandidate == AttributeEncoder.noSupport)
                                    continue;
                                double[] candidateVal = thisThreadSetAggregates.get(curCandidate);
                                if (candidateVal == null) {
                                    thisThreadSetAggregates.put(curCandidate,
                                            Arrays.copyOf(aRows[rowNum], numAggregates));
                                } else {
                                    for (int a = 0; a < numAggregates; a++) {
                                        candidateVal[a] += aRows[rowNum][a];
                                    }
                                }
                            }
                        }
                    } else if (curOrderFinal == 2) {
                        for (int colNumOne = curThreadNum; colNumOne < numColumns + curThreadNum; colNumOne++) {
                            int[] curColumnOneAttributes = byThreadAttributesTranspose[curThreadNum][colNumOne % numColumns];
                            for (int colNumTwo = colNumOne + 1; colNumTwo < numColumns + curThreadNum; colNumTwo++) {
                                int[] curColumnTwoAttributes = byThreadAttributesTranspose[curThreadNum][colNumTwo % numColumns];
                                // Aggregate candidates.  This loop uses a hybrid system where common candidates are
                                // perfect-hashed for speed while less common ones are stored in a hash table.
                                for (int rowNum = startIndex; rowNum < endIndex; rowNum++) {
                                    int rowNumInCol = rowNum - startIndex;
                                    // Only examine a pair if both its members have minimum support.
                                    if (curColumnOneAttributes[rowNumInCol] == AttributeEncoder.noSupport
                                            || curColumnTwoAttributes[rowNumInCol] == AttributeEncoder.noSupport
                                            || !singleNextArray[curColumnOneAttributes[rowNumInCol]]
                                            || !singleNextArray[curColumnTwoAttributes[rowNumInCol]])
                                        continue;
                                    long curCandidate = IntSetAsLong.twoIntToLong(curColumnOneAttributes[rowNumInCol],
                                            curColumnTwoAttributes[rowNumInCol],
                                            logCardinality);
                                    double[] candidateVal = thisThreadSetAggregates.get(curCandidate);
                                    if (candidateVal == null) {
                                        thisThreadSetAggregates.put(curCandidate,
                                                Arrays.copyOf(aRows[rowNum], numAggregates));
                                    } else {
                                        for (int a = 0; a < numAggregates; a++) {
                                            candidateVal[a] += aRows[rowNum][a];
                                        }
                                    }
                                }
                            }
                        }
                    } else if (curOrderFinal == 3) {
                        for (int colNumOne = 0; colNumOne < numColumns; colNumOne++) {
                            int[] curColumnOneAttributes = byThreadAttributesTranspose[curThreadNum][colNumOne % numColumns];
                            for (int colNumTwo = colNumOne + 1; colNumTwo < numColumns; colNumTwo++) {
                                int[] curColumnTwoAttributes = byThreadAttributesTranspose[curThreadNum][colNumTwo % numColumns];
                                for (int colnumThree = colNumTwo + 1; colnumThree < numColumns; colnumThree++) {
                                    int[] curColumnThreeAttributes = byThreadAttributesTranspose[curThreadNum][colnumThree % numColumns];
                                    // Aggregate candidates.  This loop uses a hybrid system where common candidates are
                                    // perfect-hashed for speed while less common ones are stored in a hash table.
                                    for (int rowNum = startIndex; rowNum < endIndex; rowNum++) {
                                        int rowNumInCol = rowNum - startIndex;
                                        // Only construct a triple if all its singleton members have minimum support.
                                        if (curColumnOneAttributes[rowNumInCol] == AttributeEncoder.noSupport
                                                || curColumnTwoAttributes[rowNumInCol] == AttributeEncoder.noSupport
                                                || curColumnThreeAttributes[rowNumInCol] == AttributeEncoder.noSupport
                                                || !singleNextArray[curColumnThreeAttributes[rowNumInCol]]
                                                || !singleNextArray[curColumnOneAttributes[rowNumInCol]]
                                                || !singleNextArray[curColumnTwoAttributes[rowNumInCol]])
                                            continue;
                                        long curCandidate = IntSetAsLong.threeIntToLong(
                                                curColumnOneAttributes[rowNumInCol],
                                                curColumnTwoAttributes[rowNumInCol],
                                                curColumnThreeAttributes[rowNumInCol],
                                                logCardinality);
                                        if (!precalculatedCandidates.contains(curCandidate)) {
                                            continue;
                                        }
                                        double[] candidateVal = thisThreadSetAggregates.get(curCandidate);
                                        if (candidateVal == null) {
                                            thisThreadSetAggregates.put(curCandidate,
                                                    Arrays.copyOf(aRows[rowNum], numAggregates));
                                        } else {
                                            for (int a = 0; a < numAggregates; a++) {
                                                candidateVal[a] += aRows[rowNum][a];
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        throw new MacrobaseInternalError("High Order not supported");
                    }
                    doneSignal.countDown();
                };
                // Run numThreads lambdas in separate threads
                Thread APrioriLinearThread = new Thread(APrioriLinearRunnable);
                APrioriLinearThread.start();
            }
            // Wait for all threads to finish running.
            try {
                doneSignal.await();
            } catch (InterruptedException ex) {ex.printStackTrace();}


            Long2ObjectOpenHashMap<double []> setAggregates = new Long2ObjectOpenHashMap<>();
            // Collect the aggregates stored in the per-thread hashmaps.
            for (FastFixedHashTable set : threadSetAggregates) {
                for (long curCandidateKey : set.keySet()) {
                    double[] curCandidateValue = set.get(curCandidateKey);
                    double[] candidateVal = setAggregates.get(curCandidateKey);
                    if (candidateVal == null) {
                        setAggregates.put(curCandidateKey, Arrays.copyOf(curCandidateValue, numAggregates));
                    } else {
                        for (int a = 0; a < numAggregates; a++) {
                            candidateVal[a] += curCandidateValue[a];
                        }
                    }
                }
            }

            // Prune all the collected aggregates
            LongOpenHashSet curOrderNext = new LongOpenHashSet();
            LongOpenHashSet curOrderSaved = new LongOpenHashSet();
            int pruned = 0;
            for (long curCandidate: setAggregates.keySet()) {
                if (curOrder == 1 && curCandidate == AttributeEncoder.noSupport) {
                    continue;
                }
                double[] curAggregates = setAggregates.get(curCandidate);
                boolean canPassThreshold = true;
                boolean isPastThreshold = true;
                for (int i = 0; i < qualityMetrics.length; i++) {
                    QualityMetric q = qualityMetrics[i];
                    double t = thresholds[i];
                    canPassThreshold &= q.maxSubgroupValue(curAggregates) >= t;
                    isPastThreshold &= q.value(curAggregates) >= t;
                }
                if (canPassThreshold) {
                    // if a set is already past the threshold on all metrics,
                    // save it and no need for further exploration
                    if (isPastThreshold) {
                        curOrderSaved.add(curCandidate);
                    }
                    else {
                        // otherwise if a set still has potentially good subsets,
                        // save it for further examination
                        curOrderNext.add(curCandidate);
                    }
                } else {
                    pruned++;
                }
            }

            System.out.printf("Order: %d setAggregates: %d curOrderNext: %d\n", curOrder, setAggregates.keySet().size(), curOrderNext.size());

            Long2ObjectOpenHashMap<double []> curSavedAggregates = new Long2ObjectOpenHashMap<>(curOrderSaved.size());
            for (long curSaved : curOrderSaved) {
                curSavedAggregates.put(curSaved, setAggregates.get(curSaved));
            }
            savedAggregates.put(curOrder, curSavedAggregates);
            setNext.put(curOrder, curOrderNext);
            if (curOrder == 1) {
                singleNext = new LongOpenHashSet(curOrderNext.size());
                singleNextArray = new boolean[cardinality];
                for (long i : curOrderNext) {
                    singleNext.add(i);
                    // No need to hash because only one int in the long
                    singleNextArray[(int) i] = true;
                }
            }
            log.info("Time spent in order {}: {}", curOrder, System.currentTimeMillis() - startTime);
        }

        List<APLExplanationResult> results = new ArrayList<>();
        for (int curOrder: savedAggregates.keySet()) {
            Long2ObjectOpenHashMap<double []> curOrderSavedAggregates = savedAggregates.get(curOrder);
            for (long curSet : curOrderSavedAggregates.keySet()) {
                double[] aggregates = curOrderSavedAggregates.get(curSet);
                double[] metrics = new double[qualityMetrics.length];
                for (int i = 0; i < metrics.length; i++) {
                    metrics[i] = qualityMetrics[i].value(aggregates);
                }
                results.add(
                        new APLExplanationResult(qualityMetrics, curSet, aggregates, metrics, logCardinality)
                );
            }
        }
        return results;
    }

    /**
     * Precalculate all possible candidates of order 3 to speed up candidate
     * generation.
     * @param o2Candidates All candidates of order 2 with minimum support.
     * @param singleCandidates All singleton candidates with minimum support.
     * @param logCardinality Number of bits in IntSetAsLong representations.
     * @return Possible candidates of order 3.
     */
    private FastFixedHashSet  precalculateCandidates(HashMap<Integer, Integer> columnDecoder,
                                                    LongOpenHashSet o2Candidates,
                                                    LongOpenHashSet singleCandidates,
                                                    long logCardinality) {
        LongOpenHashSet candidates = new LongOpenHashSet(o2Candidates.size() * singleCandidates.size() / 2);
        for (long pCandidate : o2Candidates) {
            for (long sCandidate : singleCandidates) {
                if (!IntSetAsLong.contains(pCandidate, sCandidate, logCardinality)) {
                    long nCandidate = IntSetAsLong.threeIntToLongSorted(IntSetAsLong.getFirst(pCandidate,
                            logCardinality), IntSetAsLong.getSecond(pCandidate, logCardinality),
                            sCandidate, logCardinality, columnDecoder);
                    candidates.add(nCandidate);
                }
            }
        }
        // Hybrid system places extremely common potential candidates in perfect hash array for quick access
        // while returning the rest in a hashmap.
        FastFixedHashSet finalCandidates = new FastFixedHashSet(100000);
        long subPair;
        for (long curCandidate : candidates) {
            subPair = IntSetAsLong.twoIntToLong(IntSetAsLong.getFirst(curCandidate, logCardinality),
                    IntSetAsLong.getSecond(curCandidate, logCardinality),
                    logCardinality);
            if (o2Candidates.contains(subPair)) {
                subPair = IntSetAsLong.twoIntToLong(IntSetAsLong.getSecond(curCandidate, logCardinality),
                        IntSetAsLong.getThird(curCandidate, logCardinality),
                        logCardinality);
                if (o2Candidates.contains(subPair)) {
                    subPair = IntSetAsLong.twoIntToLong(IntSetAsLong.getFirst(curCandidate, logCardinality),
                            IntSetAsLong.getThird(curCandidate, logCardinality),
                            logCardinality);
                    if (o2Candidates.contains(subPair)) {
                        finalCandidates.add(curCandidate);

                    }
                }
            }
        }
        return finalCandidates;
    }
}
