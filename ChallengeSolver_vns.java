package org.sbpo2025.challenge;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.util.stream.Stream;
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
    private final long MAX_RUNTIME = 600000; // milliseconds; 1 minutes

    protected List<Map<Integer, Integer>> orders;
    protected List<Map<Integer, Integer>> aisles;
    protected int nItems;
    protected int waveSizeLB;
    protected int waveSizeUB;
    protected IloNumVar t;

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

        buildModel(cplex, x, y);
        System.out.println("CPLEX model has been built.");

        // Instantiate and run your VNS-TS solver

        long heuristic_time = 60000;

        System.out.println("🚀 Starting VNS-TS Heuristic to generate a high-quality warm start...");
        VnsTsHybridSolver vnsTsSolver = new VnsTsHybridSolver();
        HeuristicSolution warmStartSolution = vnsTsSolver.solveWithTimeLimit(heuristic_time); // Give heuristic 30 seconds

        // --- 2. Feed the Warm Start to CPLEX ---
        if (warmStartSolution != null) {
            try {

                ChallengeSolution heuristicChallengeSolution = new ChallengeSolution(warmStartSolution.orders(), warmStartSolution.aisles());
                System.out.println("Heuristic found a solution! Objective: " + computeObjectiveFunction(heuristicChallengeSolution));

                double heuristicObjective = warmStartSolution.objectiveValue();
                System.out.println("Heuristic found a solution! Objective: " + heuristicObjective);
                
                // Prepare the arrays for the CPLEX MIP start
                double[] xValues = new double[orders.size()];
                for (int i = 0; i < orders.size(); i++) {
                    xValues[i] = warmStartSolution.orders().contains(i) ? 1.0 : 0.0;
                }

                double[] yValues = new double[aisles.size()];
                for (int j = 0; j < aisles.size(); j++) {
                    yValues[j] = warmStartSolution.aisles().contains(j) ? 1.0 : 0.0;
                }
                
                // Combine variables and values into a single array
                IloNumVar[] allVars = new IloNumVar[x.length + y.length + 1];
                double[] allValues = new double[xValues.length + yValues.length + 1];

                System.arraycopy(x, 0, allVars, 0, x.length);
                System.arraycopy(y, 0, allVars, x.length, y.length);
                System.arraycopy(xValues, 0, allValues, 0, xValues.length);
                System.arraycopy(yValues, 0, allValues, xValues.length, yValues.length);

                allVars[allVars.length - 1] = t;
                allValues[allValues.length - 1] = heuristicObjective;

                // Add the warm start to CPLEX
                cplex.addMIPStart(allVars, allValues);
                System.out.println("MIP Start from VNS-TS successfully added to CPLEX.");

            } catch (IloException e) {
                System.err.println("Error adding MIP Start: " + e.getMessage());
            }
        } else {
            System.out.println("VNS-TS did not find a feasible solution to use as a warm start.");
        }

        // --- 3. Build and Run CPLEX for the remaining time ---
        
        cplex.setParam(IloCplex.Param.TimeLimit, getRemainingTime(stopWatch));
        cplex.setParam(IloCplex.Param.MIP.Display, 2);
        cplex.setParam(IloCplex.Param.Emphasis.MIP, 1);
        cplex.setParam(IloCplex.Param.MIP.Strategy.HeuristicFreq, -1); // Turn off CPLEX heuristics if you trust yours

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

    protected void buildModel(IloCplex cplex, IloIntVar[] x, IloIntVar[] y) throws IloException {
        System.out.println("[DEBUG] Entering buildModel...");
        int nOrders = orders.size();
        int nAisles = aisles.size();
        int base = 2;

        // Decision variables
        System.out.println("[DEBUG] Creating decision variables t and w.");
        t = cplex.numVar(0, Double.POSITIVE_INFINITY, "t");
    
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

        // addStrengtheningCuts(cplex, x, y, sum_y, totalUnitsPicked, t);

        System.out.println("[DEBUG] Calling linearizeDenominator...");
        linearizeDenominator(cplex, sum_y, t, totalUnitsPicked, base, nAisles);

        System.out.println("[DEBUG] Setting objective to maximize t.");
        cplex.addMaximize(t);
        System.out.println("[DEBUG] Exiting buildModel.");
    }

    private void linearizeDenominator(IloCplex cplex, IloLinearNumExpr sum_y, IloNumVar t,
                                  IloLinearNumExpr totalUnitsPicked, int base, int nAisles) throws IloException {
        System.out.println("[DEBUG] Entering linearizeDenominator...");
        // 1. Calculate P (the number of "digits") needed for the chosen base
        // This is log_base(nAisles)
        int P_Y = (nAisles == 0) ? 1 : (int) Math.floor(Math.log(nAisles) / Math.log(base)) + 2;

        

        System.out.println("Base: " + base + ", P_Y: " + P_Y);
        long num_z_vars = (long)P_Y * base ;
        System.out.println("[DEBUG] Number of 'z' variables to be created: " + num_z_vars);


        // 2. Create decision variables with sizes dependent on the base
        IloIntVar[][] z = new IloIntVar[P_Y][base];
        IloNumVar[][] t_hat = new IloNumVar[P_Y][base];
        IloNumVar w = cplex.numVar(0, Double.POSITIVE_INFINITY, "w");
        int t_hat_ub = nAisles;

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

        System.out.println("[DEBUG] Adding strengthening cuts...");

        // --- (0) Denominator safety + bounds ---
        cplex.addGe(sum_y, 1.0, "at_least_one_aisle");
        cplex.addLe(sum_y, aisles.size(), "at_most_all_aisles");
        cplex.addLe(t, waveSizeUB, "t_upper_bound");

        // --- (1) Per-item max-capacity cover cut ---
        int[] Ck_max = new int[nItems];
        for (int k = 0; k < nItems; k++) {
            int mx = 0;
            for (int j = 0; j < aisles.size(); j++) {
                mx = Math.max(mx, aisles.get(j).getOrDefault(k, 0));
            }
            Ck_max[k] = mx;
        }

        for (int k = 0; k < nItems; k++) {
            if (Ck_max[k] == 0) continue; // item never available
            IloLinearNumExpr lhs = cplex.linearNumExpr();
            for (int j = 0; j < aisles.size(); j++) {
                if (aisles.get(j).containsKey(k)) {
                    lhs.addTerm(1.0, y[j]);
                }
            }
            IloLinearNumExpr rhs = cplex.linearNumExpr();
            for (int i = 0; i < orders.size(); i++) {
                int dem = orders.get(i).getOrDefault(k, 0);
                if (dem > 0) rhs.addTerm(dem, x[i]);
            }
            cplex.addGe(cplex.prod(Ck_max[k], lhs), rhs, "cover_item_" + k);
        }

        // --- (2) Per-order, per-item strengthening cut ---
        for (int i = 0; i < orders.size(); i++) {
            for (Map.Entry<Integer,Integer> e : orders.get(i).entrySet()) {
                int k = e.getKey();
                int dem = e.getValue();
                IloLinearNumExpr capFromVisited = cplex.linearNumExpr();
                for (int j = 0; j < aisles.size(); j++) {
                    int cap = aisles.get(j).getOrDefault(k, 0);
                    if (cap > 0) {
                        capFromVisited.addTerm(Math.min(cap, dem), y[j]);
                    }
                }
                cplex.addGe(capFromVisited, cplex.prod(dem, x[i]), 
                            "order_" + i + "_item_" + k);
            }
        }

        System.out.println("[DEBUG] Strengthening cuts added.");
    }
    public class VnsTsHybridSolver {

    // --- Problem Data & Parameters ---
        private final List<Integer> allOrderIds;
        private final Random rand = new Random();
        private Map<Integer, Integer> tabuList;
        private int[] orderTotalUnits;
        private final int K_MAX = 5;
        private final int VNS_MAX_ITER_NO_IMPROVE = 500;
        private final int TS_MAX_ITER = 10;
        private final int TABU_TENURE = 10;

        private class Solution {
        final Set<Integer> orders;
        final Set<Integer> aisles;
        final double objectiveValue;
        

            Solution(Set<Integer> orders, Set<Integer> aisles, double objectiveValue) {
                this.orders = orders;
                this.aisles = aisles;
                this.objectiveValue = objectiveValue;
            }
        }

    private record OrderInfo(int id, int totalUnits, double density) {}

    private Map<Integer, List<Integer>> itemToAislesMap;

    public VnsTsHybridSolver() {
            this.allOrderIds = new ArrayList<>();
            for (int i = 0; i < orders.size(); i++) {
                this.allOrderIds.add(i);
            }
            this.tabuList = new HashMap<>();

            // --- PRE-COMPUTATION FOR ACCELERATION ---
            System.out.println("[Heuristic] Pre-computing item-to-aisle map...");
            this.itemToAislesMap = new HashMap<>();
            for (int i = 0; i < nItems; i++) {
                this.itemToAislesMap.put(i, new ArrayList<>());
            }
            // Since VnsTsHybridSolver is an inner class, it can access 'aisles'
            for (int j = 0; j < aisles.size(); j++) {
                for (int itemId : aisles.get(j).keySet()) {
                    this.itemToAislesMap.get(itemId).add(j);
                }
            }
            System.out.println("[Heuristic] Pre-computing order total units...");
            this.orderTotalUnits = new int[orders.size()];
            for (int i = 0; i < orders.size(); i++) {
                this.orderTotalUnits[i] = orders.get(i).values().stream().mapToInt(v -> v).sum();
            }
            System.out.println("[Heuristic] Pre-computation finished.");
        }

    /**
     * Main method to run the hybrid VNS-TS algorithm.
     */
   public HeuristicSolution solveWithTimeLimit(long timeLimitMillis) {
            StopWatch heuristicStopWatch = new StopWatch();
            heuristicStopWatch.start();
        // 1. Get a good initial solution
        Solution globalBestSolution = generateGuaranteedInitialSolution();
        System.out.printf("Initial Solution Objective: %.4f%n", calculateObjective(globalBestSolution));

        int iter = 0;
        while (iter < VNS_MAX_ITER_NO_IMPROVE) {
            System.out.printf("Iteration %d/%d...%n", iter + 1, VNS_MAX_ITER_NO_IMPROVE);

            if (heuristicStopWatch.getTime() >= timeLimitMillis) {
                System.out.println("Heuristic time limit reached.");
                break;
            }
            int k = 1;
            while (k <= K_MAX) {
                // 2. Shake: Create a new starting point S' from the k-th neighborhood
                Solution s_prime = shake(globalBestSolution, k);

                // 3. Tabu Search: Run TS from S' to find a local optimum S''
                Solution s_double_prime = tabuSearch(s_prime, iter, heuristicStopWatch, timeLimitMillis);

                // 4. Move or Not: Compare the new solution with the global best
                if (calculateObjective(s_double_prime) > calculateObjective(globalBestSolution)) {
                    globalBestSolution = s_double_prime;
                    System.out.printf("VNS found new best! Obj: %.4f, k=%d%n", calculateObjective(globalBestSolution), k);
                    k = 1; // Found improvement, go back to the first neighborhood
                    iter = 0; // Reset main iteration counter
                } else {
                    System.out.printf("No improvement found in k=%d neighborhood. Current best: %.4f%n", k, calculateObjective(globalBestSolution));
                    k++; // No improvement, try a bigger neighborhood
                }
            }
            iter++;
        }
        System.out.println("Hybrid VNS-TS finished. Final Objective: " + calculateObjective(globalBestSolution));
        if (globalBestSolution != null) {
            return new HeuristicSolution(globalBestSolution.orders, globalBestSolution.aisles, globalBestSolution.objectiveValue);
        }
        return null;
    }
    private Solution buildSolutionIfFeasible(Set<Integer> currentOrders) {
    // By default, enforce ALL constraints
    return buildSolutionIfFeasible(currentOrders, false);
}

private Solution buildSolutionIfFeasible(Set<Integer> currentOrders, boolean ignoreWaveSizeLB) {
    if (currentOrders == null || currentOrders.isEmpty()) {
        return null;
    }

    // Calculate total demand
    int[] totalDemand = new int[nItems];
    int totalUnits = 0;
    for (int orderId : currentOrders) {
        for (Map.Entry<Integer, Integer> entry : orders.get(orderId).entrySet()) {
            totalDemand[entry.getKey()] += entry.getValue();
            totalUnits += entry.getValue();
        }
    }

    // --- MODIFIED CHECK ---
    // Check wave size constraints
    if (totalUnits > waveSizeUB) { // Always check upper bound
        return null;
    }
    if (!ignoreWaveSizeLB && totalUnits < waveSizeLB) { // Check lower bound only if requested
        return null;
    }
    // --------------------

    // Find minimal aisles (assuming findMinimalAislesGreedy exists and is correct)
    Set<Integer> requiredAisles = findMinimalAislesGreedy(totalDemand);
    if (requiredAisles == null || (requiredAisles.isEmpty() && totalUnits > 0)) {
        return null;
    }

    // Check stock availability with the selected aisles
    int[] totalStock = new int[nItems];
    for (int aisleId : requiredAisles) {
        for (Map.Entry<Integer, Integer> entry : aisles.get(aisleId).entrySet()) {
            totalStock[entry.getKey()] += entry.getValue();
        }
    }

    for (int item = 0; item < nItems; item++) {
        if (totalDemand[item] > totalStock[item]) {
            return null;
        }
    }
    
    // An objective of 0 is useless, so treat it as infeasible.
    if (requiredAisles.isEmpty()) return null;

    double objective = (double) totalUnits / requiredAisles.size();
    return new Solution(currentOrders, requiredAisles, objective);
}

    private Solution generateGuaranteedInitialSolution() {
    List<Integer> sortedOrderIds = new ArrayList<>(allOrderIds);
    // Sort orders by density to start with the most promising candidates
    sortedOrderIds.sort(Comparator.comparingDouble((Integer id) -> {
        // ... (sorting logic is the same as before) ...
        Map<Integer, Integer> order = orders.get(id);
        int totalUnits = order.values().stream().mapToInt(Integer::intValue).sum();
        if (totalUnits == 0) return 0.0;
        Set<Integer> requiredAisles = new HashSet<>();
        for (int item : order.keySet()) {
            // Instant lookup instead of a loop
            requiredAisles.addAll(this.itemToAislesMap.get(item));
        }
        return requiredAisles.isEmpty() ? 0.0 : (double) totalUnits / requiredAisles.size();
    }).reversed());

    // Iterate through every order as a potential starting "seed"
    for (int seedOrderId : sortedOrderIds) {
        Set<Integer> currentOrders = new HashSet<>();
        currentOrders.add(seedOrderId);

        // Try to grow this seed by adding more orders
        for (int otherOrderId : sortedOrderIds) {
            if (currentOrders.contains(otherOrderId)) {
                continue;
            }
            
            currentOrders.add(otherOrderId); // Tentatively add the new order

            // Check feasibility using the RELAXED checker (ignore lower bound)
            Solution tempSolution = buildSolutionIfFeasible(currentOrders, true);
            
            if (tempSolution == null) {
                // This addition made the solution infeasible (e.g., > waveSizeUB or stock issue)
                currentOrders.remove(otherOrderId); // Backtrack
            }
        }
        
        // After building the largest possible batch from the seed, run the FINAL, STRICT check
        Solution finalSolution = buildSolutionIfFeasible(currentOrders, false); // 'false' enforces the LB
        
        if (finalSolution != null) {
            // Success! This batch is fully valid.
            System.out.println("Found a guaranteed feasible initial solution.");
            return finalSolution;
        }
    }

    System.err.println("CRITICAL: Could not generate ANY feasible initial solution after trying all seeds.");
    return null; // All attempts failed
}

    /**
     * A simple wrapper to get the objective value from a solution object.
     */
    private double calculateObjective(Solution solution) {
        // Gracefully handle null or invalid solutions
        return (solution != null) ? solution.objectiveValue : -1.0;
    }


    private Solution shake(Solution solution, int k) {
    if (solution == null || solution.orders.isEmpty()) {
        return solution; // Cannot shake an invalid solution
    }

    final int MAX_TRIES = 20; // Try multiple times to find a valid random move
    Solution shakenSolution = null;

    System.out.printf("Shaking solution with k=%d...%n", k);
    for (int i = 0; i < MAX_TRIES; i++) {
        Set<Integer> tempOrders = new HashSet<>(solution.orders);

        if (k == 1) { // N1: Perform one random Swap-1 (swap one in, one out)
            if (performRandomSwap(tempOrders)) {
                shakenSolution = buildSolutionIfFeasible(tempOrders);
            }
        } else if (k == 2) { // N2: Perform one random Add or Remove
            if (rand.nextBoolean()) { // 50/50 chance to add or remove
                if (performRandomAdd(tempOrders)) {
                    shakenSolution = buildSolutionIfFeasible(tempOrders);
                }
            } else {
                if (performRandomRemove(tempOrders)) {
                    shakenSolution = buildSolutionIfFeasible(tempOrders);
                }
            }
        } else if (k == 3) { // N3: Perform two random Swap-1 moves
            if (performRandomSwap(tempOrders) && performRandomSwap(tempOrders)) {
                 shakenSolution = buildSolutionIfFeasible(tempOrders);
            }
        }

        if (shakenSolution != null) {
            System.out.printf("Shaken solution found after %d tries! Objective: %.4f%n", i + 1, calculateObjective(shakenSolution));
            return shakenSolution; // Found a valid shaken solution
        }
    }

    // If all tries failed to produce a valid new solution, return the original
    System.out.println("Failed to find a valid shaken solution after " + MAX_TRIES + " attempts.");
    return solution;
}

    // --- Shake Helper Methods ---

    private boolean performRandomSwap(Set<Integer> currentOrders) {
        List<Integer> selected = new ArrayList<>(currentOrders);
        List<Integer> unselected = allOrderIds.stream()
                .filter(id -> !currentOrders.contains(id))
                .collect(Collectors.toList());

        if (selected.isEmpty() || unselected.isEmpty()) {
            return false; // Cannot perform a swap
        }

        int orderToRemove = selected.get(rand.nextInt(selected.size()));
        int orderToAdd = unselected.get(rand.nextInt(unselected.size()));

        currentOrders.remove(orderToRemove);
        currentOrders.add(orderToAdd);
        return true;
    }

    private boolean performRandomAdd(Set<Integer> currentOrders) {
        List<Integer> unselected = allOrderIds.stream()
                .filter(id -> !currentOrders.contains(id))
                .collect(Collectors.toList());

        if (unselected.isEmpty()) {
            return false; // No orders to add
        }

        int orderToAdd = unselected.get(rand.nextInt(unselected.size()));
        currentOrders.add(orderToAdd);
        return true;
    }

    private boolean performRandomRemove(Set<Integer> currentOrders) {
        if (currentOrders.size() <= 1) { // Avoid removing the last order
            return false;
        }
        List<Integer> selected = new ArrayList<>(currentOrders);
        int orderToRemove = selected.get(rand.nextInt(selected.size()));
        currentOrders.remove(orderToRemove);
        return true;
    }

    private Solution tabuSearch(Solution initialSolution, int currentVnsIteration, StopWatch stopWatch, long timeLimit) {
    if (initialSolution == null || calculateObjective(initialSolution) == -1.0) {
        return initialSolution;
    }

    this.tabuList.clear();
    Solution bestSolutionInThisRun = initialSolution;
    Solution currentSolution = initialSolution;

    List<Integer> selectedOrdersList = new ArrayList<>(currentSolution.orders);
    List<Integer> unselectedOrdersList = new ArrayList<>(allOrderIds);
    unselectedOrdersList.removeAll(selectedOrdersList);

    int[] currentTotalDemand = new int[nItems];
    int currentTotalUnits = 0;
    for (int orderId : selectedOrdersList) {
        for (Map.Entry<Integer, Integer> entry : orders.get(orderId).entrySet()) {
            currentTotalDemand[entry.getKey()] += entry.getValue();
        }
        currentTotalUnits += this.orderTotalUnits[orderId];
    }

    final int NEIGHBORHOOD_SAMPLE_SIZE = 50; // Tuned down for speed

    for (int i = 0; i < TS_MAX_ITER; i++) {
        if (stopWatch.getTime() >= timeLimit) {
            System.out.println("Tabu Search hit time limit. Terminating early.");
            break;
        }

        int currentGlobalIteration = currentVnsIteration * TS_MAX_ITER + i;
        Solution bestNeighborFound = null;
        int bestOrderToAdd = -1, bestOrderToRemove = -1;

        int movesToEvaluate = Math.min(NEIGHBORHOOD_SAMPLE_SIZE, selectedOrdersList.size() * unselectedOrdersList.size());
        if (movesToEvaluate == 0) break;

        for (int move = 0; move < movesToEvaluate; move++) {
            int removeIndex = rand.nextInt(selectedOrdersList.size());
            int addIndex = rand.nextInt(unselectedOrdersList.size());
            int orderToRemove = selectedOrdersList.get(removeIndex);
            int orderToAdd = unselectedOrdersList.get(addIndex);
            
            int neighborTotalUnits = currentTotalUnits - this.orderTotalUnits[orderToRemove] + this.orderTotalUnits[orderToAdd];
            if (neighborTotalUnits < waveSizeLB || neighborTotalUnits > waveSizeUB) continue;

            int[] neighborDemand = Arrays.copyOf(currentTotalDemand, currentTotalDemand.length);
            for (Map.Entry<Integer, Integer> entry : orders.get(orderToRemove).entrySet()) {
                neighborDemand[entry.getKey()] -= entry.getValue();
            }
            for (Map.Entry<Integer, Integer> entry : orders.get(orderToAdd).entrySet()) {
                neighborDemand[entry.getKey()] += entry.getValue();
            }

            Set<Integer> neighborOrders = new HashSet<>(selectedOrdersList);
            neighborOrders.remove(orderToRemove);
            neighborOrders.add(orderToAdd);
            Solution neighbor = checkFeasibilityFromDemand(neighborOrders, neighborDemand, neighborTotalUnits);

            if (neighbor == null) continue;

            boolean isTabu = tabuList.getOrDefault(orderToRemove, 0) > currentGlobalIteration
                             || tabuList.getOrDefault(orderToAdd, 0) > currentGlobalIteration;
            boolean aspirationMet = calculateObjective(neighbor) > calculateObjective(bestSolutionInThisRun);

            if (!isTabu || aspirationMet) {
                if (bestNeighborFound == null || calculateObjective(neighbor) > calculateObjective(bestNeighborFound)) {
                    bestNeighborFound = neighbor;
                    bestOrderToAdd = orderToAdd;
                    bestOrderToRemove = orderToRemove;
                }
            }
        }

        if (bestNeighborFound == null) break; 
        
        currentSolution = bestNeighborFound;
        
        // --- BUG FIX: Update state incrementally instead of recalculating from scratch ---
        currentTotalUnits = currentTotalUnits - this.orderTotalUnits[bestOrderToRemove] + this.orderTotalUnits[bestOrderToAdd];
        for (Map.Entry<Integer, Integer> entry : orders.get(bestOrderToRemove).entrySet()) {
            currentTotalDemand[entry.getKey()] -= entry.getValue();
        }
        for (Map.Entry<Integer, Integer> entry : orders.get(bestOrderToAdd).entrySet()) {
            currentTotalDemand[entry.getKey()] += entry.getValue();
        }
        // ---------------------------------------------------------------------------------
        
        selectedOrdersList.remove(Integer.valueOf(bestOrderToRemove));
        unselectedOrdersList.add(bestOrderToRemove);
        unselectedOrdersList.remove(Integer.valueOf(bestOrderToAdd));
        selectedOrdersList.add(bestOrderToAdd);
        
        tabuList.put(bestOrderToAdd, currentGlobalIteration + TABU_TENURE);
        tabuList.put(bestOrderToRemove, currentGlobalIteration + TABU_TENURE);

        if (calculateObjective(currentSolution) > calculateObjective(bestSolutionInThisRun)) {
            bestSolutionInThisRun = currentSolution;
        }
    }
    return bestSolutionInThisRun;
}
/**
 * A helper method that performs only the expensive parts of a feasibility check,
 * assuming total demand and units have already been calculated.
 */
private Solution checkFeasibilityFromDemand(Set<Integer> orders, int[] totalDemand, int totalUnits) {
    Set<Integer> requiredAisles = findMinimalAislesGreedy(totalDemand);
    if (requiredAisles == null || (requiredAisles.isEmpty() && totalUnits > 0)) {
        return null;
    }

    // Check stock availability
    int[] totalStock = new int[nItems];
    for (int aisleId : requiredAisles) {
        for (Map.Entry<Integer, Integer> entry : aisles.get(aisleId).entrySet()) {
            totalStock[entry.getKey()] += entry.getValue();
        }
    }
    for (int item = 0; item < nItems; item++) {
        if (totalDemand[item] > totalStock[item]) {
            return null;
        }
    }

    double objective = (double) totalUnits / requiredAisles.size();
    return new Solution(orders, requiredAisles, objective);
}

    private Set<Integer> findMinimalAislesGreedy(int[] totalDemand) {
    Set<Integer> selectedAisles = new HashSet<>();
    Set<Integer> uncoveredItems = new HashSet<>();
    for (int i = 0; i < totalDemand.length; i++) {
        if (totalDemand[i] > 0) uncoveredItems.add(i);
    }

    // --- OPTIMIZATION: Build a set of candidate aisles to check ---
    Set<Integer> candidateAisles = new HashSet<>();
    for (int item : uncoveredItems) {
        candidateAisles.addAll(this.itemToAislesMap.get(item));
    }

    while (!uncoveredItems.isEmpty()) {
        int bestAisle = -1;
        int maxCoverage = -1;

        // --- OPTIMIZATION: Loop only over relevant candidates, not all aisles ---
        for (int aisleId : candidateAisles) {
            int currentCoverage = 0;
            for (int item : aisles.get(aisleId).keySet()) {
                if (uncoveredItems.contains(item)) {
                    currentCoverage++;
                }
            }
            if (currentCoverage > maxCoverage) {
                maxCoverage = currentCoverage;
                bestAisle = aisleId;
            }
        }
        
        if (bestAisle == -1) return null; // Failure to cover all items
        
        selectedAisles.add(bestAisle);
        candidateAisles.remove(bestAisle); // Don't check this aisle again

        final int finalBestAisle = bestAisle;
        uncoveredItems.removeIf(item -> aisles.get(finalBestAisle).containsKey(item));
    }
    
    return selectedAisles;
}
    
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