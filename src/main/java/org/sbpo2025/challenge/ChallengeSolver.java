package org.sbpo2025.challenge;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.util.stream.Stream;
import java.util.Iterator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.Random;
import java.util.stream.Collectors;
import org.apache.commons.lang3.time.StopWatch;

public class ChallengeSolver {
    private final long MAX_RUNTIME = 600000; // milliseconds; 10 minutes

    protected List<Map<Integer, Integer>> orders;
    protected List<Map<Integer, Integer>> aisles;
    protected int nItems;
    protected int waveSizeLB;
    protected int waveSizeUB;
    protected IloNumVar t;
    protected IloNumVar w;

    // print instance size
    public void printInstanceSize() {
        System.out.println("Number of orders: " + orders.size());
        System.out.println("Number of aisles: " + aisles.size());
        System.out.println("Number of items: " + nItems);
        System.out.println("Wave size lower bound: " + waveSizeLB);
        System.out.println("Wave size upper bound: " + waveSizeUB);
    }

    class Solution {
        private final Set<Integer> orders;
        public Solution(Set<Integer> orders) { this.orders = orders; }
        public Set<Integer> getOrders() { return Collections.unmodifiableSet(orders); }
    }
    class Order {
        private final int id;
        public Order(int id) { this.id = id; }
        public int getId() { return id; }
    }


    public ChallengeSolver(
        List<Map<Integer, Integer>> orders,
        List<Map<Integer, Integer>> aisles,
        int nItems, 
        int waveSizeLB, 
        int waveSizeUB) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
    }

    protected record HeuristicSolution(Set<Integer> orders, Set<Integer> aisles, double objectiveValue) {}

public ChallengeSolution solve(StopWatch stopWatch) {
    IloCplex cplex = null;
    try {
        cplex = new IloCplex();


        IloIntVar[] x = cplex.boolVarArray(orders.size());
        IloIntVar[] y = cplex.boolVarArray(aisles.size());

        
        // Instantiate and run your VNS-TS solver

        long heuristic_time_limit_ms = 30000; // 30 seconds
        System.out.println("Running simple greedy heuristic for up to " + (heuristic_time_limit_ms / 1000) + "s as a warm start...");
        boolean runOnlyConstructor = true;

        HeuristicSolution warmStartSolution = runRefinementHeuristic(heuristic_time_limit_ms, stopWatch, runOnlyConstructor);

        buildModel(cplex, x, y);
        System.out.println("CPLEX model has been built.");
    
        if (warmStartSolution != null) {
        try {
            System.out.printf("Heuristic found a solution! Objective: %.2f%n", warmStartSolution.objectiveValue());

            // --- MIP START: FIXING ONLY THE 'x' VARIABLES ---

            // 1. Create arrays containing ONLY the x variables and their values.
            IloNumVar[] mipStartVars = new IloNumVar[x.length];
            double[] mipStartVals = new double[x.length];

            // 2. Populate values ONLY for the x variables
            for (int i = 0; i < x.length; i++) {
                mipStartVars[i] = x[i];
                mipStartVals[i] = warmStartSolution.orders().contains(i) ? 1.0 : 0.0;
            }
            
            // 3. Add the partial warm start and change the effort level to SolveMIP.
            // This instructs CPLEX to fix these x-values and solve for the best y-values.
            cplex.addMIPStart(mipStartVars, mipStartVals, IloCplex.MIPStartEffort.SolveMIP);
            
            System.out.println("Partial MIP Start (only x) with SolveMIP effort successfully added to CPLEX.");

        } catch (IloException e) {
            System.err.println("Error adding MIP Start: " + e.getMessage());
        }
    } else {
        System.out.println("Heuristic did not find a feasible solution to use as a warm start.");
    }
        // --- 3. Build and Run CPLEX for the remaining time ---
        
        cplex.setParam(IloCplex.Param.TimeLimit, getRemainingTime(stopWatch));
        cplex.setParam(IloCplex.Param.MIP.Display, 2);
        cplex.setParam(IloCplex.Param.Emphasis.MIP, IloCplex.MIPEmphasis.HiddenFeas);
        // cplex.setParam(IloCplex.Param.MIP.Strategy.RINSHeur, 1);
        // cplex.setParam(IloCplex.Param.MIP.Strategy.NodeSelect, 2);

        if (cplex.solve()) {
            System.out.println("Solution found by CPLEX!");
            System.out.println("Final Objective value: " + cplex.getObjValue());

            Set<Integer> selectedOrders = new HashSet<>();
            Set<Integer> visitedAisles = new HashSet<>();
            
            for(int i = 0; i < orders.size(); i++) {
                if (cplex.getValue(x[i]) > 0.5) {
                    selectedOrders.add(i);
                }
            }
            for(int j = 0; j < aisles.size(); j++) {
                if (cplex.getValue(y[j]) > 0.5) {
                    visitedAisles.add(j);
                }
            }
            
            return new ChallengeSolution(selectedOrders, visitedAisles);
        } else {
            System.err.println("CPLEX could not find a feasible solution.");
            // If CPLEX fails but the heuristic found a solution, you could return the heuristic's solution
            if (warmStartSolution != null) {
                return new ChallengeSolution(warmStartSolution.orders(), warmStartSolution.aisles());
            }
            return null;
        }

    } catch (IloException e) {
        e.printStackTrace();
        return null;
    } finally {
        if (cplex != null) {
            cplex.end();
        }
    }
}

    private record ScoredAisle(int id, int score) implements Comparable<ScoredAisle> {
    // Sorts aisles in descending order of their score.
    @Override
    public int compareTo(ScoredAisle other) {
        return Integer.compare(other.score, this.score);
    }
}

private HeuristicSolution runRefinementHeuristic(long timeLimitMillis, StopWatch overallStopWatch, boolean runOnlyConstructor) {
    StopWatch heuristicStopWatch = StopWatch.createStarted();
    System.out.println("Running two-phase 'Construct and Refine' Heuristic...");

    // --- Phase 1: Construct one high-quality initial solution ---
    List<ScoredAisle> originalRankedAisles = scoreAndSortAisles();
    HeuristicSolution bestSolutionSoFar = constructSolutionFromRankedAisles(originalRankedAisles, 150);

    if (bestSolutionSoFar == null) {
        System.out.println("Initial construction failed to find a feasible solution.");
        return null; // Cannot proceed without a starting point.
    }

    if (runOnlyConstructor) {
        System.out.println("Run-once flag is true. Skipping refinement phase and returning initial solution.");
        if (bestSolutionSoFar != null) {
            System.out.printf("Initial solution found with obj: %.2f.%n", bestSolutionSoFar.objectiveValue());
        }
        return bestSolutionSoFar;
    }
    System.out.printf("Initial solution found with obj: %.2f. Starting refinement phase...%n", bestSolutionSoFar.objectiveValue());

    // --- Create a map for quick score lookups: aisleId -> score ---
    Map<Integer, Integer> aisleScoreMap = new HashMap<>();
    for(ScoredAisle sa : originalRankedAisles) {
        aisleScoreMap.put(sa.id(), sa.score());
    }
    
    List<List<Integer>> itemToAislesMap = buildItemToAislesMap();
    Random rand = new Random();
    int iterations = 0;
    long lastPrintTime = 0;
    while (heuristicStopWatch.getTime() < timeLimitMillis && getRemainingTime(overallStopWatch) > 5) {
        long currentTime = heuristicStopWatch.getTime();
        if (currentTime - lastPrintTime >= 10000) {
            System.out.printf("Heuristic remaining time: %.1fs%n", (timeLimitMillis - currentTime) / 1000.0);
            lastPrintTime = currentTime;
        }

        
        // 1. Identify the "worst" aisles IN the solution
        List<Integer> aislesInSolution = new ArrayList<>(bestSolutionSoFar.aisles());
        // Sort by score (ascending) to find the worst ones at the beginning of the list
        aislesInSolution.sort(Comparator.comparingInt(aisleId -> aisleScoreMap.getOrDefault(aisleId, 0)));
        
        // 2. Identify the "best" aisles OUTSIDE the solution
        List<Integer> topAislesOutside = new ArrayList<>();
        for (ScoredAisle sa : originalRankedAisles) {
            if (!bestSolutionSoFar.aisles().contains(sa.id())) {
                topAislesOutside.add(sa.id());
            }
        }

        if (aislesInSolution.isEmpty() || topAislesOutside.isEmpty()) {
            break; // Cannot perform a swap
        }

        // 3. Define the neighborhood: swap the bottom 5% of aisles
        int numToSwap = Math.max(1, (int)(aislesInSolution.size() * 0.05));
        List<Integer> bottomAisles = aislesInSolution.subList(0, Math.min(numToSwap, aislesInSolution.size()));
        
        // 4. Create a new candidate solution by swapping
        // Start with the core (top 95%) of the current best solution
        Set<Integer> candidateAisleSet = new HashSet<>(aislesInSolution.subList(bottomAisles.size(), aislesInSolution.size()));
        
        // Add an equivalent number of the best aisles from the outside
        Collections.shuffle(topAislesOutside);
        for(int i = 0; i < numToSwap && i < topAislesOutside.size(); i++) {
            candidateAisleSet.add(topAislesOutside.get(i));
        }

        // 5. Re-solve the subproblem with the new set of aisles
        HeuristicSolution candidateSolution = findBestOrdersForAisleSet(candidateAisleSet, itemToAislesMap);

        // 6. If the new solution is an improvement, accept it
        if (candidateSolution != null && candidateSolution.objectiveValue() > bestSolutionSoFar.objectiveValue()) {
            bestSolutionSoFar = candidateSolution;
            System.out.printf("  -> Refinement found new best: %.2f (at iteration %d)%n", bestSolutionSoFar.objectiveValue(), iterations);
            // After an improvement, we must re-calculate the "in" and "out" lists in the next loop
        }
        iterations++;
    }
    
    System.out.printf("Refinement Heuristic finished after %d iterations.%n", iterations);
    return bestSolutionSoFar;
}


/**
 * Helper method that performs one full, deterministic construction of a solution
 * based on a given ranking of aisles.
 *
 * @param rankedAisles A list of aisles, sorted by preference.
 * @return The best HeuristicSolution found for this specific ranking.
 */
private HeuristicSolution constructSolutionFromRankedAisles(List<ScoredAisle> rankedAisles, int maxAislesToTest) {
    HeuristicSolution bestSolutionForThisRun = null;
    List<List<Integer>> itemToAislesMap = buildItemToAislesMap();
    
    Set<Integer> currentAisleSet = new HashSet<>();
    int loopLimit = Math.min(maxAislesToTest, rankedAisles.size());

    for (int k = 0; k < loopLimit; k++) {
        currentAisleSet.add(rankedAisles.get(k).id());
        HeuristicSolution candidateSolution = findBestOrdersForAisleSet(currentAisleSet, itemToAislesMap);
        
        if (candidateSolution != null) {
            // CORRECTED LINE: Compare the candidate to the best solution for this run.
            if (bestSolutionForThisRun == null || candidateSolution.objectiveValue() > bestSolutionForThisRun.objectiveValue()) {
                bestSolutionForThisRun = candidateSolution;
            }
        }
    }
    return bestSolutionForThisRun;
}

/**
 * Calculates an importance score for each aisle and returns a sorted list.
 * The score is based on how many single-item orders the aisle can satisfy.
 */
private List<ScoredAisle> scoreAndSortAisles() {
    List<List<Integer>> itemToAislesMap = buildItemToAislesMap();
    int[] aisleScores = new int[aisles.size()];

    // Boost score for aisles that contain items for sparse (e.g., single-item) orders.
    for (int i = 0; i < orders.size(); i++) {
        if (orders.get(i).size() == 1) { // This is a sparse, single-item order.
            int item = orders.get(i).keySet().iterator().next();
            if (item < itemToAislesMap.size()) {
                for (int aisleId : itemToAislesMap.get(item)) {
                    aisleScores[aisleId]++;
                }
            }
        }
    }

    List<ScoredAisle> scoredAisles = new ArrayList<>();
    for (int j = 0; j < aisles.size(); j++) {
        scoredAisles.add(new ScoredAisle(j, aisleScores[j]));
    }

    Collections.sort(scoredAisles);
    return scoredAisles;
}

/**
 * Sub-problem solver: Given a fixed set of aisles, finds the best set of orders.
 */
private HeuristicSolution findBestOrdersForAisleSet(Set<Integer> aisleSet, List<List<Integer>> itemToAislesMap) {
    // A helper class to sort orders by their size (number of units).
    class OrderBySize implements Comparable<OrderBySize> {
        final int id;
        final int units;
        OrderBySize(int id, int units) { this.id = id; this.units = units; }
        @Override
        public int compareTo(OrderBySize other) { return Integer.compare(other.units, this.units); }
    }

    // --- Step 1: Filter to find all orders whose items are PRESENT in the aisleSet ---
    List<OrderBySize> satisfiableOrders = new ArrayList<>();
    for (int i = 0; i < orders.size(); i++) {
        boolean isPotentiallySatisfiable = true;
        for (int item : orders.get(i).keySet()) {
            boolean itemFound = false;
            if (item < itemToAislesMap.size()) {
                for (int aisleId : itemToAislesMap.get(item)) {
                    if (aisleSet.contains(aisleId)) {
                        itemFound = true;
                        break;
                    }
                }
            }
            if (!itemFound) {
                isPotentiallySatisfiable = false;
                break;
            }
        }

        if (isPotentiallySatisfiable) {
            int totalUnits = orders.get(i).values().stream().mapToInt(Integer::intValue).sum();
            satisfiableOrders.add(new OrderBySize(i, totalUnits));
        }
    }

    // --- Step 2: Calculate the total available supply (our "inventory budget") ---
    Map<Integer, Integer> availableSupply = new HashMap<>();
    for (int aisleId : aisleSet) {
        for (Map.Entry<Integer, Integer> entry : aisles.get(aisleId).entrySet()) {
            availableSupply.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    // --- Step 3: Sort satisfiable orders (largest first) ---
    Collections.sort(satisfiableOrders);

    // --- Step 4: Greedily "Pack and Check" orders into the wave ---
    Set<Integer> selectedOrders = new HashSet<>();
    int currentTotalUnits = 0;

    for (OrderBySize orderToTry : satisfiableOrders) {
        // Check 1: Does it fit within the wave size?
        if (currentTotalUnits + orderToTry.units > this.waveSizeUB) {
            continue; // Too large, try the next one.
        }

        // Check 2: Is there enough inventory for every item in this order?
        boolean hasEnoughInventory = true;
        Map<Integer, Integer> orderDemand = orders.get(orderToTry.id);
        for (Map.Entry<Integer, Integer> demandEntry : orderDemand.entrySet()) {
            if (demandEntry.getValue() > availableSupply.getOrDefault(demandEntry.getKey(), 0)) {
                hasEnoughInventory = false;
                break;
            }
        }

        // If both checks pass, add the order to the solution!
        if (hasEnoughInventory) {
            selectedOrders.add(orderToTry.id);
            currentTotalUnits += orderToTry.units;

            // And decrement our "inventory budget"
            for (Map.Entry<Integer, Integer> demandEntry : orderDemand.entrySet()) {
                availableSupply.compute(demandEntry.getKey(), (k, v) -> v - demandEntry.getValue());
            }
        }
    }

    // Step 5: Validate wave size lower bound and return the solution.
    if (currentTotalUnits >= this.waveSizeLB) {
        double objective = aisleSet.isEmpty() ? 0 : (double) currentTotalUnits / aisleSet.size();
        return new HeuristicSolution(selectedOrders, aisleSet, objective);
    }

    return null;
}

    /**
     * Helper method to build the item -> [aisles] map for fast lookups.
     */
    private List<List<Integer>> buildItemToAislesMap() {
        List<List<Integer>> map = new ArrayList<>(this.nItems);
        for (int k = 0; k < this.nItems; k++) {
            map.add(new ArrayList<>());
        }
        for (int j = 0; j < this.aisles.size(); j++) {
            for (int k : this.aisles.get(j).keySet()) {
                map.get(k).add(j);
            }
        }
        return map;
    }

    protected void buildModel(IloCplex cplex, IloIntVar[] x, IloIntVar[] y) throws IloException {
        System.out.println("[DEBUG] Entering buildModel...");
        int nOrders = orders.size();
        int nAisles = aisles.size();
        int base = 2;

        // Decision variables
        System.out.println("[DEBUG] Creating decision variables t and w.");
        this.t = cplex.numVar(0, Double.POSITIVE_INFINITY, "t");
        this.w = cplex.numVar(0, Double.POSITIVE_INFINITY, "w");

        // Constraints
        // Constraint 1: The total units picked for each item cannot exceed the total units available in the visited aisles.
        System.out.println("[DEBUG] Adding item availability constraints...");
        for (int k = 0; k < nItems; k++) {
            IloLinearNumExpr totalUnitsPickedForItem = cplex.linearNumExpr();
            for (int i = 0; i < nOrders; i++) {
                if (orders.get(i).containsKey(k)) {
                    totalUnitsPickedForItem.addTerm(orders.get(i).get(k), x[i]);
                }
            }

            IloLinearNumExpr totalUnitsAvailableForItem = cplex.linearNumExpr();
            for (int j = 0; j < nAisles; j++) {
                if (aisles.get(j).containsKey(k)) {
                    totalUnitsAvailableForItem.addTerm(aisles.get(j).get(k), y[j]);
                }
            }
            cplex.addLe(totalUnitsPickedForItem, totalUnitsAvailableForItem);
        }
        System.out.println("[DEBUG] Item availability constraints added.");

        System.out.println("[DEBUG] Adding necessary aisles constraints...");
        for (int i = 0; i < nOrders; i++) {
        // For each item 'k' in the current order 'i'
        for (int k : orders.get(i).keySet()) {
            IloLinearNumExpr necessaryAisles = cplex.linearNumExpr();
            // Find all aisles 'j' that contain item 'k'
            for (int j = 0; j < nAisles; j++) {
                if (aisles.get(j).containsKey(k)) {
                    necessaryAisles.addTerm(1, y[j]);
                }
            }
            // If order 'i' is selected (x[i]=1), then the sum of y[j] for necessary 
            // aisles must be at least 1 (i.e., we must visit at least one).
            cplex.addLe(x[i], necessaryAisles);
        }
    }
        System.out.println("[DEBUG] Necessary aisles constraints added.");

        // Constraint 2: The total number of units picked must be within the wave size bounds.
        System.out.println("[DEBUG] Adding wave size constraints...");
        IloLinearNumExpr totalUnitsPicked = cplex.linearNumExpr();
        for (int i = 0; i < nOrders; i++) {
            for (int units : orders.get(i).values()) {
                totalUnitsPicked.addTerm(units, x[i]);
            }
        }

        IloLinearNumExpr sum_y = cplex.linearNumExpr();
            for (int i = 0; i < y.length; i++) {
                sum_y.addTerm(1, y[i]);
            }

        cplex.addGe(totalUnitsPicked, waveSizeLB);
        cplex.addLe(totalUnitsPicked, waveSizeUB);
        cplex.addLe(t, waveSizeUB);              // since t ≤ totalUnitsPicked/1 ≤ waveSizeUB
        System.out.println("[DEBUG] Wave size constraints added.");

        double tighterUB = calculateTighterUpperBound();
        System.out.printf("[INFO] Trivial Upper Bound: %.2f, Tighter Upper Bound: %.2f%n", (double)waveSizeUB, tighterUB);
        cplex.addLe(t, tighterUB, "t_tighter_upper_bound");

        // addStrengtheningCuts(cplex, x, y, sum_y, totalUnitsPicked, t);

        System.out.println("[DEBUG] Calling linearizeDenominator...");
        linearizeDenominator(cplex, sum_y, t, totalUnitsPicked, base, nAisles, tighterUB);

        System.out.println("[DEBUG] Setting objective to maximize t.");
        cplex.addMaximize(t);
        System.out.println("[DEBUG] Exiting buildModel.");
    }

    private void linearizeDenominator(IloCplex cplex, IloLinearNumExpr sum_y, IloNumVar t,
                                  IloLinearNumExpr totalUnitsPicked, int base, int nAisles, double tighterUB) throws IloException {
        System.out.println("[DEBUG] Entering linearizeDenominator...");
        // 1. Calculate P (the number of "digits") needed for the chosen base
        // This is log_base(nAisles)
        int P_Y = (nAisles == 0) ? 1 : (int) Math.floor(Math.log(nAisles) / Math.log(base)) + 1;

        System.out.println("Base: " + base + ", P_Y: " + P_Y);
        long num_z_vars = (long)P_Y * base ;
        System.out.println("[DEBUG] Number of 'z' variables to be created: " + num_z_vars);


        // 2. Create decision variables with sizes dependent on the base
        IloIntVar[][] z = new IloIntVar[P_Y][base];
        IloNumVar[][] t_hat = new IloNumVar[P_Y][base];

        int t_hat_ub = (int) Math.ceil(tighterUB);

        IloLinearNumExpr sum_y_representation = cplex.linearNumExpr();

        System.out.println("[DEBUG] Building sum_y representation...");
        for (int p = 0; p < P_Y; p++) {
            z[p] = cplex.boolVarArray(base);
            t_hat[p] = cplex.numVarArray(base, 0, t_hat_ub);
            for (int b = 0; b < base; b++) {;
                double coefficient = Math.pow(base, p) * b;
                sum_y_representation.addTerm(coefficient, z[p][b]);
                cplex.addLe(t_hat[p][b], cplex.prod(z[p][b], t_hat_ub));
            }
        }

        cplex.addEq(sum_y_representation, sum_y);

        IloLinearNumExpr sum_w_representation = cplex.linearNumExpr();

        for (int p = 0; p < P_Y; p++) {
            for (int b = 0; b < base; b++) {
                double coefficient = Math.pow(base, p) * b;
                sum_w_representation.addTerm(coefficient, t_hat[p][b]);
            }
        }

        cplex.addEq(sum_w_representation, w);


        for (int p = 0; p < P_Y; p++) {
            IloLinearNumExpr z_l_sum = cplex.linearNumExpr();
            IloLinearNumExpr t_hat_sum = cplex.linearNumExpr();
            for (int b = 0; b < base; b++) {
                z_l_sum.addTerm(1.0, z[p][b]);
                t_hat_sum.addTerm(1.0, t_hat[p][b]);
            }
            cplex.addEq(z_l_sum, 1.0);
            cplex.addEq(t_hat_sum, t);
        }

        // w <= totalUnitsPicked
        cplex.addLe(w, totalUnitsPicked);
        System.out.println("[DEBUG] Exiting linearizeDenominator...");
    }

    private void addStrengtheningCuts(
    IloCplex cplex,
    IloIntVar[] x,
    IloIntVar[] y,
    IloLinearNumExpr sum_y,
    IloLinearNumExpr totalUnitsPicked,
    IloNumVar t) throws IloException {

    System.out.println("[DEBUG] Adding STATIC strengthening cuts...");

    // --- Pre-computation for efficiency ---
    List<List<Integer>> itemToAislesMap = new ArrayList<>(nItems);
    for (int k = 0; k < nItems; k++) {
        itemToAislesMap.add(new ArrayList<>());
    }
    for (int j = 0; j < aisles.size(); j++) {
        for (int k : aisles.get(j).keySet()) {
            itemToAislesMap.get(k).add(j);
        }
    }

    // --- Cut (0): Denominator must be at least 1 ---
    cplex.addGe(sum_y, 1.0, "at_least_one_aisle");

    // --- Cut (1): Per-item max-capacity cover cut ---
    for (int k = 0; k < nItems; k++) {
        List<Integer> aislesWithItemK = itemToAislesMap.get(k);
        if (aislesWithItemK.isEmpty()) continue;

        int Ck_max = 0;
        for (int j : aislesWithItemK) {
            Ck_max = Math.max(Ck_max, aisles.get(j).getOrDefault(k, 0));
        }
            
        if (Ck_max == 0) continue;

        IloLinearNumExpr lhsExpr = cplex.linearNumExpr();
        for (int j : aislesWithItemK) {
            lhsExpr.addTerm(1.0, y[j]);
        }

        IloLinearNumExpr rhsExpr = cplex.linearNumExpr();
        for (int i = 0; i < orders.size(); i++) {
            rhsExpr.addTerm(orders.get(i).getOrDefault(k, 0), x[i]);
        }
        
        cplex.addGe(cplex.prod(Ck_max, lhsExpr), rhsExpr, "cover_item_" + k);
    }
    
    // --- Mutually Exclusive Orders Cuts (Pre-computation) ---
    Map<Integer, Integer> totalItemSupply = new HashMap<>();
    for (Map<Integer, Integer> aisle : aisles) {
        for (Map.Entry<Integer, Integer> entry : aisle.entrySet()) {
            totalItemSupply.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }
    
    List<int[]> incompatiblePairs = new ArrayList<>();
    for (int i = 0; i < orders.size(); i++) {
        for (int j = i + 1; j < orders.size(); j++) {
            for (int k : orders.get(i).keySet()) {
                if (orders.get(j).containsKey(k)) {
                    int combinedDemand = orders.get(i).get(k) + orders.get(j).get(k);
                    if (combinedDemand > totalItemSupply.getOrDefault(k, 0)) {
                        incompatiblePairs.add(new int[]{i, j});
                        break;
                    }
                }
            }
        }
    }

    // Now add these cuts directly to the model
    for (int[] pair : incompatiblePairs) {
        cplex.addLe(cplex.sum(x[pair[0]], x[pair[1]]), 1.0);
    }

    System.out.println("[DEBUG] Static strengthening cuts added.");
}

    private double calculateTighterUpperBound() {
        // 1. Calculate the total units in each aisle.
        List<Integer> aisleTotals = new ArrayList<>();
        for (Map<Integer, Integer> aisle : this.aisles) {
            int sum = 0;
            for (int units : aisle.values()) {
                sum += units;
            }
            aisleTotals.add(sum);
        }

        // 2. Sort the aisle totals in descending order (densest first).
        aisleTotals.sort(Collections.reverseOrder());

        // 3. Greedily sum the units from the densest aisles until UB is met or exceeded.
        long cumulativeSum = 0;
        int aisleCount = 0;
        for (int total : aisleTotals) {
            if (cumulativeSum >= this.waveSizeUB) {
                break;
            }
            cumulativeSum += total;
            aisleCount++;
        }

        // 4. Calculate the new upper bound: UB / k (where k is the aisle count).
        // Handle edge cases to avoid division by zero.
        if (aisleCount == 0) {
            return (double) this.waveSizeUB; // Fallback to the trivial bound
        }

        return (double) this.waveSizeUB / aisleCount;
    }


    protected long getRemainingTime(StopWatch stopWatch) {
        return Math.max(
                TimeUnit.SECONDS.convert(MAX_RUNTIME - stopWatch.getTime(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS),
                0) - 10; // Subtract 10 seconds to create a safety buffer
    }

    protected boolean isSolutionFeasible(ChallengeSolution challengeSolution) {
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return false;
        }

        int[] totalUnitsPicked = new int[nItems];
        int[] totalUnitsAvailable = new int[nItems];

        // Calculate total units picked
        for (int order : selectedOrders) {
            for (Map.Entry<Integer, Integer> entry : orders.get(order).entrySet()) {
                totalUnitsPicked[entry.getKey()] += entry.getValue();
            }
        }

        // Calculate total units available
        for (int aisle : visitedAisles) {
            for (Map.Entry<Integer, Integer> entry : aisles.get(aisle).entrySet()) {
                totalUnitsAvailable[entry.getKey()] += entry.getValue();
            }
        }

        // Check if the total units picked are within bounds
        int totalUnits = Arrays.stream(totalUnitsPicked).sum();
        if (totalUnits < waveSizeLB || totalUnits > waveSizeUB) {
            return false;
        }

        // Check if the units picked do not exceed the units available
        for (int i = 0; i < nItems; i++) {
            if (totalUnitsPicked[i] > totalUnitsAvailable[i]) {
                return false;
            }
        }

        return true;
    }


    protected double computeObjectiveFunction(ChallengeSolution challengeSolution) {
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return 0.0;
        }
        int totalUnitsPicked = 0;

        // Calculate total units picked
        for (int order : selectedOrders) {
            totalUnitsPicked += orders.get(order).values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
        }

        // Calculate the number of visited aisles
        int numVisitedAisles = visitedAisles.size();

        // Objective function: total units picked / number of visited aisles
        return (double) totalUnitsPicked / numVisitedAisles;
    }
}

