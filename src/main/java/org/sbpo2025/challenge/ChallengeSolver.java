package org.sbpo2025.challenge;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

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

import org.apache.commons.lang3.time.StopWatch;

public class ChallengeSolver {
    private final long MAX_RUNTIME = 600000; // milliseconds; 10 minutes
    protected List<Map<Integer, Integer>> orders;
    protected List<Map<Integer, Integer>> aisles;
    protected int nItems;
    protected int waveSizeLB;
    protected int waveSizeUB;
    protected double tLowerBound;
    protected double tUpperBound;
    protected List<Integer> quantidade_pedidos;
    protected List<Integer> quantidade_corredor;
    

    // static {
    //     try {
    //         // Try to load from standard library path first
    //         System.loadLibrary("cplex");
    //         // System.out.println("Loaded CPLEX library from system path");
    //     } catch (UnsatisfiedLinkError e) {
    //         System.err.println("System load failed, trying explicit path...");
    //         try {
    //             // Explicit path to your CPLEX library
    //             System.load("/opt/ibm/ILOG/CPLEX_Studio2211/cplex/bin/x86-64_linux/libcplex2211.so");
    //             // System.out.println("Loaded CPLEX library from explicit path");
    //         } catch (UnsatisfiedLinkError e2) {
    //             // System.err.println("CRITICAL: Failed to load CPLEX library");
    //             // System.err.println("Error: " + e2.getMessage());
    //             // System.err.println("Please check your CPLEX installation at /opt/ibm/ILOG/CPLEX_Studio2211/cplex");
    //             System.exit(1);
    //         }
    //     }
    // }

    public ChallengeSolver(
            List<Map<Integer, Integer>> orders,
            List<Map<Integer, Integer>> aisles,
            int nItems,
            int waveSizeLB,
            int waveSizeUB,
            double tLowerBound,
            double tUpperBound) {
            this.orders = orders;
            this.aisles = aisles;
            this.nItems = nItems;
            this.waveSizeLB = waveSizeLB;
            this.waveSizeUB = waveSizeUB;
            this.tLowerBound = tLowerBound;
            this.tUpperBound = tUpperBound;

        

        // Compute soma_pedidos
        this.quantidade_pedidos = new ArrayList<>();
        for (Map<Integer, Integer> order : orders) {
            int total = order.values().stream().mapToInt(Integer::intValue).sum();
            this.quantidade_pedidos.add(total);
        }

        // Compute soma_corredor
        this.quantidade_corredor = new ArrayList<>();
        for (Map<Integer, Integer> aisle : aisles) {
            int total = aisle.values().stream().mapToInt(Integer::intValue).sum();
            this.quantidade_corredor.add(total);
        }
    }

    // ==================== BRANCH MANAGEMENT ====================
    private static class Branch {
        final int aMin;
        final int aMax;
        final int depth;
        final double parentObj;
        final int activeInterval; // Active interval from parent solution
        final Set<Integer> parentOrderSolution;  // NEW: Store order solution
        final Set<Integer> parentAisleSolution;   // NEW: Store aisle solution

        Branch(int aMin, int aMax, int depth, double parentObj, int activeInterval,
        Set<Integer> parentOrderSolution, Set<Integer> parentAisleSolution) {
            this.aMin = aMin;
            this.aMax = aMax;
            this.depth = depth;
            this.parentObj = parentObj;
            this.activeInterval = activeInterval;
            this.parentOrderSolution = parentOrderSolution;  // NEW
            this.parentAisleSolution = parentAisleSolution;   // NEW
        }
    }

    // ==================== RESULT CONTAINER ====================
    private static class McCormickResult {
        boolean feasible = false;
        Set<Integer> selectedOrders;
        Set<Integer> selectedAisles;
        double gap;
        double obj;
        ChallengeSolution solution;
        List<IloConstraint> constraints;
        int j_selected = -1; // Active McCormick interval index
        boolean optimal;
    }

    public ChallengeSolution solve(StopWatch stopWatch) {
        try {
            long deadline = MAX_RUNTIME - 10000; // 10 minutes minus 10 seconds for writing output
            // System.out.println("SOLVER START: " + stopWatch.getTime() + "ms");

            // ==================== GENERAL VARIABLES AND MISC ====================
            int nOrders = orders.size();
            int nAisles = aisles.size();
            int N = 5; // Number of intervals for piecewise McCormick
            double best_obj = 0;
            Map<Integer, Double> upperBounds = new HashMap<>();
            Set<Integer> bestOrders = new HashSet<>();
            Set<Integer> bestAisles = new HashSet<>();

            System.out.println("==================== Starting Solver ====================");

            // ========================== LP SOLVER INITIALIZATION ==============================
            long lpSetupStart = stopWatch.getTime();
            IloCplex lp_cplex = new IloCplex();
            lp_cplex.setOut(null);
            lp_cplex.setWarning(null);
            lp_cplex.setParam(IloCplex.Param.Simplex.Display, 0);
            lp_cplex.setParam(IloCplex.Param.MIP.Display, 0);
            lp_cplex.setParam(IloCplex.Param.Tune.Display, 0);
            lp_cplex.setParam(IloCplex.Param.Network.Display, 0);

            // LP variables
            IloNumVar[] X_lp = new IloNumVar[nOrders];
            for (int i = 0; i < nOrders; i++) {
                X_lp[i] = lp_cplex.numVar(0.0, 1.0, "Xlp_" + i);
            }
            IloNumVar[] Y_lp = new IloNumVar[nAisles];
            for (int j = 0; j < nAisles; j++) {
                Y_lp[j] = lp_cplex.numVar(0.0, 1.0, "Ylp_" + j);
            }

            // LP constraints
            IloLinearNumExpr totalItemsExprLP = lp_cplex.linearNumExpr();
            for (int i = 0; i < nOrders; i++) {
                totalItemsExprLP.addTerm(quantidade_pedidos.get(i), X_lp[i]);
            }
            lp_cplex.addGe(totalItemsExprLP, waveSizeLB);
            lp_cplex.addLe(totalItemsExprLP, waveSizeUB);

            for (int item = 0; item < nItems; item++) {
                IloLinearNumExpr itemExpr = lp_cplex.linearNumExpr();
                for (int i = 0; i < nOrders; i++) {
                    Integer qty = orders.get(i).get(item);
                    if (qty != null) itemExpr.addTerm(qty, X_lp[i]);
                }
                for (int j = 0; j < nAisles; j++) {
                    Integer qty = aisles.get(j).get(item);
                    if (qty != null) itemExpr.addTerm(-qty, Y_lp[j]);
                }
                lp_cplex.addLe(itemExpr, 0);
            }
            // System.out.println("LP solver setup: " + (stopWatch.getTime() - lpSetupStart) + "ms");


            // ========================== MILP SOLVER INITIALIZATION ==============================
            long milpSetupStart = stopWatch.getTime();
            IloCplex mc_cplex = new IloCplex();
            mc_cplex.setOut(null);
            mc_cplex.setWarning(null);
            mc_cplex.setParam(IloCplex.Param.Simplex.Display, 0);
            mc_cplex.setParam(IloCplex.Param.MIP.Display, 0);
            mc_cplex.setParam(IloCplex.Param.Tune.Display, 0);
            mc_cplex.setParam(IloCplex.Param.Network.Display, 0);

            // MILP variables
            IloIntVar[] X_mc = mc_cplex.boolVarArray(nOrders);
            IloIntVar[] Y_mc = mc_cplex.boolVarArray(nAisles);
            IloNumVar[] A_hat = new IloNumVar[N];
            IloNumVar[] t_hat = new IloNumVar[N];
            IloNumVar[] z = new IloIntVar[N];
            for (int j = 0; j < N; j++) {
                A_hat[j] = mc_cplex.numVar(0, nAisles, "A_hat_" + j);
                t_hat[j] = mc_cplex.numVar(0, tUpperBound, "t_hat_" + j);
                z[j] = mc_cplex.boolVar("z_" + j);
            }
            IloNumVar Avar = mc_cplex.numVar(0, nAisles, "Avar");
            IloNumVar t = mc_cplex.numVar(tLowerBound, tUpperBound, "t");
            IloNumVar w = mc_cplex.numVar(0, tUpperBound * nAisles, "w");

            // MILP constraints
            mc_cplex.addMaximize(t);
            IloLinearNumExpr totalItemsExprMC = mc_cplex.linearNumExpr();
            for (int i = 0; i < nOrders; i++) {
                totalItemsExprMC.addTerm(quantidade_pedidos.get(i), X_mc[i]);
            }
            mc_cplex.addGe(totalItemsExprMC, waveSizeLB);
            mc_cplex.addLe(totalItemsExprMC, waveSizeUB);

            for (int item = 0; item < nItems; item++) {
                IloLinearNumExpr itemExpr = mc_cplex.linearNumExpr();
                for (int i = 0; i < nOrders; i++) {
                    Integer qty = orders.get(i).get(item);
                    if (qty != null && qty > 0) {
                        itemExpr.addTerm(qty, X_mc[i]);
                    }
                }
                for (int j = 0; j < nAisles; j++) {
                    Integer qtyAisle = aisles.get(j).get(item);
                    if (qtyAisle != null && qtyAisle > 0) {
                        itemExpr.addTerm(-qtyAisle, Y_mc[j]);
                    }
                }
                mc_cplex.addLe(itemExpr, 0);
            }

            IloLinearNumExpr Aexpr = mc_cplex.linearNumExpr();
            for (int j = 0; j < nAisles; j++) {
                Aexpr.addTerm(1.0, Y_mc[j]);
            }
            mc_cplex.addEq(Aexpr, Avar);

            IloLinearNumExpr ASumExpr = mc_cplex.linearNumExpr();
            for (int j = 0; j < N; j++) {
                ASumExpr.addTerm(1.0, A_hat[j]);
            }
            mc_cplex.addEq(Avar, ASumExpr);

            IloLinearNumExpr tSumExpr = mc_cplex.linearNumExpr();
            for (int j = 0; j < N; j++) {
                tSumExpr.addTerm(1.0, t_hat[j]);
            }
            mc_cplex.addEq(t, tSumExpr);

            mc_cplex.addLe(w, totalItemsExprMC);

            IloLinearNumExpr zSum = mc_cplex.linearNumExpr();
            for (int j = 0; j < N; j++) {
                zSum.addTerm(1.0, z[j]);
            }
            mc_cplex.addEq(zSum, 1);

            for (int i = 0; i < orders.size(); i++) {
                if (quantidade_pedidos.get(i) > waveSizeUB) {
                    mc_cplex.addEq(X_mc[i], 0);
                }
            }
            // System.out.println("MILP solver setup: " + (stopWatch.getTime() - milpSetupStart) + "ms");

            // ==================== ADD CUTS ====================
            // valid ineq.
            long cutSetupStart = stopWatch.getTime();
            int cutCount = 0;

            for (int o = 0; o < nOrders; o++) {
                Map<Integer, Integer> orderItems = orders.get(o);
                for (Integer item : orderItems.keySet()) {
                    IloLinearNumExpr aisleSumExpr = mc_cplex.linearNumExpr();
                    boolean foundAisle = false;
                    
                    for (int a = 0; a < nAisles; a++) {
                        if (aisles.get(a).containsKey(item)) {
                            aisleSumExpr.addTerm(1.0, Y_mc[a]);
                            foundAisle = true;
                        }
                    }
                    
                    if (foundAisle) {
                        mc_cplex.addLe(X_mc[o], aisleSumExpr);
                        cutCount++;
                    }
                }
            }

            // // Precompute total supply and min demand per item
            // Map<Integer, Double> totalSupplyPerItem = new HashMap<>();
            // Map<Integer, Integer> minDemandPerItem = new HashMap<>();

            // // Initialize with default values
            // for (int item = 0; item < nItems; item++) {
            //     totalSupplyPerItem.put(item, 0.0);
            //     minDemandPerItem.put(item, Integer.MAX_VALUE);
            // }

            // // Compute total supply from aisles
            // for (int a = 0; a < nAisles; a++) {
            //     Map<Integer, Integer> aisle = aisles.get(a);
            //     for (Map.Entry<Integer, Integer> entry : aisle.entrySet()) {
            //         int item = entry.getKey();
            //         int qty = entry.getValue();
            //         totalSupplyPerItem.put(item, totalSupplyPerItem.get(item) + qty);
            //     }
            // }

            // // Compute min demand from orders
            // for (int o = 0; o < nOrders; o++) {
            //     Map<Integer, Integer> order = orders.get(o);
            //     for (Map.Entry<Integer, Integer> entry : order.entrySet()) {
            //         int item = entry.getKey();
            //         int demand = entry.getValue();
            //         int currentMin = minDemandPerItem.get(item);
            //         if (demand < currentMin) {
            //             minDemandPerItem.put(item, demand);
            //         }
            //     }
            // }
            // System.out.printf("Added %d valid inequality constraints%n", cutCount);

            // Track added constraints
            // int forceZeroConstraints = 0;
            // int maxOrderConstraints = 0;

            // // Add constraints for each item
            // for (int item = 0; item < nItems; item++) {
            //     double totalSupply = totalSupplyPerItem.get(item);
            //     int minDemand = minDemandPerItem.get(item);
                
            //     // Skip if no valid demand (item not in any order)
            //     if (minDemand == Integer.MAX_VALUE) continue;
                
            //     // Handle insufficient supply
            //     if (totalSupply < minDemand) {
            //         for (int o = 0; o < nOrders; o++) {
            //             if (orders.get(o).containsKey(item)) {
            //                 mc_cplex.addEq(X_mc[o], 0);
            //                 forceZeroConstraints++;
            //             }
            //         }
            //         continue;
            //     }
                
            //     // Calculate max orders (floor division)
            //     int maxOrders = (int) Math.floor(totalSupply / minDemand);
                
            //     // Create expression for orders containing this item
            //     IloLinearNumExpr orderSumExpr = mc_cplex.linearNumExpr();
            //     boolean hasOrders = false;
                
            //     for (int o = 0; o < nOrders; o++) {
            //         if (orders.get(o).containsKey(item)) {
            //             orderSumExpr.addTerm(1.0, X_mc[o]);
            //             hasOrders = true;
            //         }
            //     }
                
            //     // Only add constraint if item exists in orders
            //     if (hasOrders) {
            //         mc_cplex.addLe(orderSumExpr, maxOrders);
            //         maxOrderConstraints++;
            //     }
            // }
            // System.out.printf("Added %d force-zero constraints, %d max-order constraints%n",
            //         forceZeroConstraints, maxOrderConstraints);
            
            // Cover cuts
            List<Integer> sortedPedidosDesc = new ArrayList<>(quantidade_pedidos);
            sortedPedidosDesc.sort(Collections.reverseOrder());
            int sumLB = 0;
            int n_min_LB = 0;
            for (int q : sortedPedidosDesc) {
                sumLB += q;
                n_min_LB++;
                if (sumLB >= waveSizeLB) break;
            }
            List<Integer> sortedPedidosAsc = new ArrayList<>(quantidade_pedidos);
            sortedPedidosAsc.sort(Comparator.naturalOrder());
            int sumUB = 0;
            int n_max_UB = 0;
            for (int q : sortedPedidosAsc) {
                if (sumUB + q > waveSizeUB) break;
                sumUB += q;
                n_max_UB++;
            }
            IloLinearNumExpr ubAccelExpr = mc_cplex.linearNumExpr();
            for (IloNumVar x : X_mc) ubAccelExpr.addTerm(1, x);
            mc_cplex.addLe(ubAccelExpr, n_max_UB);
            IloLinearNumExpr lbAccelExpr = mc_cplex.linearNumExpr();
            for (IloNumVar x : X_mc) lbAccelExpr.addTerm(1, x);
            mc_cplex.addGe(lbAccelExpr, n_min_LB);

            System.out.println("Added cuts: " + (stopWatch.getTime() - cutSetupStart) + "ms");

            // ==================== FIND A_min ====================
            long aMinStart = stopWatch.getTime();
            // System.out.println("Finding A_min...");
            int A_min = 1;
            int A_Active = nAisles;
            for (int A = 1; A <= nAisles; A++) {
                // Check time limit
                if (stopWatch.getTime() >= deadline) {
                    System.out.println("Time limit reached during A_min calculation");
                    break;
                }
                
                if (lp_cplex.getObjective() != null) {
                    lp_cplex.delete(lp_cplex.getObjective());
                }
                lp_cplex.addMaximize(lp_cplex.prod(1.0 / A, totalItemsExprLP));

                IloLinearNumExpr aisleSumExpr = lp_cplex.linearNumExpr();
                for (IloNumVar y : Y_lp) aisleSumExpr.addTerm(1.0, y);
                IloConstraint sumYConstr = lp_cplex.eq(aisleSumExpr, A);
                lp_cplex.add(sumYConstr);

                boolean solved = lp_cplex.solve();

                if (solved) {
                    double lpratio = lp_cplex.getObjValue();
                    upperBounds.put(A, lpratio);
                    A_min = A;

                    double ubDivA = (double) waveSizeUB / A;
                    double diff = Math.abs(lpratio - ubDivA);

                    if (diff < 1e-2) {
                        A_Active = A;
                    }
                    break;
                } else {
                    // System.out.printf("LP infeasible for A = %d%n", A);
                }
                lp_cplex.remove(sumYConstr);
            }
            System.out.printf("A_min is: %d%n", A_min);
            int A_max = nAisles;
            System.out.printf("A_max is: %d%n", A_max);
            // System.out.println("A_min calculation time: " + (stopWatch.getTime() - aMinStart) + "ms");

            // ==================== BRANCH AND BOUND INITIALIZATION ====================
            long branchSetupStart = stopWatch.getTime();
            Deque<Branch> activeBranches = new ArrayDeque<>(); // Stack for DFS
            // Root branch with full range and no active interval
            activeBranches.push(new Branch(A_min, A_max, 0, 0, -1, null, null));

            int maxDepth = 1000; // Maximum depth of recursion
            IloConstraint bestConstraint = null;
            final double FIXED_MCCORMICK_GAP_TOL = 1e-4; // Fixed tolerance for McCormick gap
            // System.out.println("Branch and bound setup: " + (stopWatch.getTime() - branchSetupStart) + "ms");

            // ==================== MAIN BRANCH LOOP ====================
            while (!activeBranches.isEmpty() && stopWatch.getTime() < deadline) {
                long remainingBranchTime = deadline - stopWatch.getTime();
                System.out.printf("Remaining time: %d ms%n", remainingBranchTime);
                long branchStart = stopWatch.getTime();
                Branch currentBranch = activeBranches.pop();
                int intervalWidth = currentBranch.aMax - currentBranch.aMin + 1;
                System.out.printf("\nExploring branch: A=[%d,%d] (width=%d) depth=%d activeInterval=%d%n",
                        currentBranch.aMin, currentBranch.aMax, intervalWidth, 
                        currentBranch.depth, currentBranch.activeInterval);

                // Determine MIP gap tolerance based on interval size
                double mipGapTolerance;
                if (intervalWidth <= Math.floor(nAisles / 10.0)) {
                    mipGapTolerance = 1e-4; // Tight tolerance for small intervals
                } else {
                    // Dynamic tolerance based on depth
                    mipGapTolerance = calculateDynamicGapTolerance(currentBranch.depth);
                    System.out.printf("Dynamic MIP gap tolerance for depth %d: %.6f%n", 
                                     currentBranch.depth, mipGapTolerance);
                }
                
                McCormickResult result = solveMcCormickForBranch(
                currentBranch,  // NEW: pass current branch
                currentBranch.aMin,  // Use values from branch
                currentBranch.aMax,
                N,
                mc_cplex,
                lp_cplex,
                upperBounds,
                X_lp,
                Y_lp,
                totalItemsExprLP,
                X_mc,
                Y_mc,
                A_hat,
                t_hat,
                z,
                Avar,
                t,
                w,
                waveSizeLB,
                waveSizeUB,
                nItems,
                orders,
                aisles,
                quantidade_pedidos,
                deadline - stopWatch.getTime(),
                mipGapTolerance,
                true,  // enable warm start
                best_obj
            );
                if (result.feasible) {
                    double trueObj = computeObjectiveFunction(result.solution);
                    System.out.printf("Branch solution: obj=%.4f gap=%.6f activeInterval=%d%n", 
                                     trueObj, result.gap, result.j_selected);

                    // Always update best solution if feasible and better, regardless of gap
                    if (isSolutionFeasible(result.solution)) {
                        if (trueObj > best_obj) {
                            best_obj = trueObj;
                            bestOrders = result.selectedOrders;
                            bestAisles = result.selectedAisles;
                            System.out.println("New best solution found! Objective: " + best_obj);
                            if (stopWatch.getTime() >= deadline) {
                                System.out.println("Breaking branch loop due to time limit");
                                break;
                            }

                            // Add bound constraint for future branches
                            if (bestConstraint != null) {
                                try { mc_cplex.remove(bestConstraint); } catch (IloException e) {}
                            }
                            bestConstraint = mc_cplex.addGe(t, trueObj - 1e-5);
                        }
                    }

                    // Check if we should branch further
                    boolean shouldBranch = false;
                    if (result.gap > FIXED_MCCORMICK_GAP_TOL) {
                        shouldBranch = true;
                        System.out.printf("Branching due to McCormick gap (%.6f > tolerance)%n", result.gap);
                    } else if (!result.optimal) {
                        shouldBranch = true; // Branch if not solved to optimality
                        System.out.println("Branching due to non-optimal node solution");
                    }

                    shouldBranch = shouldBranch && 
                                (currentBranch.depth < maxDepth) && 
                                (intervalWidth > 1);

                    // System.out.printf("Branch [%d-%d] total: %dms%n",
                    // currentBranch.aMin, currentBranch.aMax, (stopWatch.getTime() - branchStart));
                    
                    if (shouldBranch) {
                        // Create N new subintervals
                        int subWidth = intervalWidth / N;
                        int remainder = intervalWidth % N;
                        
                        System.out.printf("Splitting [%d,%d] (width=%d) into %d subintervals (gap=%.6f > tolerance=%.6f)%n",
                                currentBranch.aMin, currentBranch.aMax, intervalWidth, 
                                N, result.gap, FIXED_MCCORMICK_GAP_TOL);
                        
                        List<Branch> childBranches = new ArrayList<>();
                        int start = currentBranch.aMin;
                        for (int j = 0; j < N; j++) {
                            int end = start + subWidth - 1;
                            if (j < remainder) {
                                end += 1;
                            }
                            if (end > currentBranch.aMax) {
                                end = currentBranch.aMax;
                            }
                            
                            // Only create branch if interval has at least 1 integer
                            if (start <= end) {
                                // Prioritize the active interval from parent solution
                                int nextActive = (j == result.j_selected) ? result.j_selected : -1;
                                childBranches.add(new Branch(start, end, currentBranch.depth + 1, trueObj, nextActive,
                          result.selectedOrders, result.selectedAisles));  // Pass solution
                                System.out.printf("  Subinterval %d: [%d,%d] (width=%d)%n", 
                                                 j, start, end, end - start + 1);
                            }
                            start = end + 1;
                        }

                        // Push child branches to stack in reverse order, but put active branch last
                        // so it's explored next (DFS)
                        int activeIdx = result.j_selected;
                        for (int j = childBranches.size() - 1; j >= 0; j--) {
                            if (j != activeIdx) {
                                activeBranches.push(childBranches.get(j));
                            }
                        }
                        if (activeIdx >= 0 && activeIdx < childBranches.size()) {
                            activeBranches.push(childBranches.get(activeIdx));
                        }
                    } else if (result.gap <= FIXED_MCCORMICK_GAP_TOL) {
                        System.out.println("McCormick gap closed in branch, not splitting");
                    } else {
                        System.out.println("Not splitting due to depth/interval constraints");
                    }
                }else {
                        System.out.println("Solution is not feasible, prunning");
                    }

                // Cleanup constraints
                removeMcCormickConstraints(mc_cplex, result.constraints);
            }   

            // ==================== FINAL SOLUTION ====================
            System.out.println("\n==================== Final Solution ====================");
            System.out.printf("Objective (items per aisle): %.4f%n", best_obj);
            System.out.println("Orders selected: " + bestOrders.size());
            System.out.println("Aisles selected: " + bestAisles.size());
            System.out.println("========================================================");

            long totalTime = stopWatch.getTime();
            System.out.println("Total execution time: " + totalTime + " ms");
            return new ChallengeSolution(bestOrders, bestAisles);
        } catch (IloException e) {
            System.err.println("CPLEX Exception: " + e.getMessage());
            return new ChallengeSolution(Collections.emptySet(), Collections.emptySet());
        }
    }

    // Calculate dynamic gap tolerance based on depth   
    private double calculateDynamicGapTolerance(int depth) {
        // Base tolerance at root level
        double baseTolerance = 0.2; // 50% tolerance at root
        
        // Tolerance decreases exponentially with depth
        double depthFactor = Math.pow(0.7, depth);  
        double tolerance = baseTolerance * depthFactor;
        
        // Minimum tolerance to prevent excessive computation
        double minTolerance = 0.2; // 10% min tolerance
        return 0.2;
        // return Math.min(tolerance, minTolerance);
    }

    // ==================== HELPER METHODS ====================
    private McCormickResult solveMcCormickForBranch(
        Branch currentBranch,  // ADDED: current branch context
        int aMin, int aMax, int N, IloCplex mc_cplex, IloCplex lp_cplex,
        Map<Integer, Double> upperBounds, IloNumVar[] X_lp, IloNumVar[] Y_lp,
        IloLinearNumExpr totalItemsExprLP, IloIntVar[] X_mc, IloIntVar[] Y_mc,
        IloNumVar[] A_hat, IloNumVar[] t_hat, IloNumVar[] z, IloNumVar Avar,
        IloNumVar t, IloNumVar w, int waveSizeLB, int waveSizeUB, int nItems,
        List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles,
        List<Integer> quantidade_pedidos, long remainingTime, double mipGapTolerance,
        boolean useWarmStart, double best_obj
    ) {
        McCormickResult result = new McCormickResult();
        result.constraints = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        try {
            // ==================== UPDATE LP BOUNDS ====================
            long lpUpdateStart = System.currentTimeMillis();
            double delta_aisles = (double) (aMax - aMin) / N;
            for (int k = 1; k <= N - 1; k++) {
                if (System.currentTimeMillis() - startTime > remainingTime) {
                    System.out.println("Breaking LP bounds update due to time limit");
                    break;
                }
                int A_aisles = (int) Math.floor(aMin + k * delta_aisles);
                if (upperBounds.containsKey(A_aisles)) continue;

                if (lp_cplex.getObjective() != null) {
                    lp_cplex.delete(lp_cplex.getObjective());
                }
                lp_cplex.addMaximize(lp_cplex.prod(1.0 / A_aisles, totalItemsExprLP));

                IloLinearNumExpr aisleSumExpr = lp_cplex.linearNumExpr();
                for (IloNumVar y : Y_lp) aisleSumExpr.addTerm(1.0, y);
                IloConstraint sumYConstr = lp_cplex.eq(aisleSumExpr, A_aisles);
                lp_cplex.add(sumYConstr);

                boolean solved = lp_cplex.solve();

                if (solved) {
                    double lpratio = lp_cplex.getObjValue();
                    upperBounds.put(A_aisles, lpratio);
                } else {
                    upperBounds.put(A_aisles, (double) waveSizeUB / A_aisles);
                }
                lp_cplex.remove(sumYConstr);
            }
            System.out.println("LP bounds update: " + (System.currentTimeMillis() - lpUpdateStart) + "ms");

            // ==================== ADD MCCORMICK CONSTRAINTS ====================
            long constraintsStart = System.currentTimeMillis();
            for (int j = 0; j < N; j++) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > remainingTime) {
                    System.out.println("Breaking McCormick setup due to time");
                    break;
                }
                int A_lb = (int) Math.floor(aMin + j * delta_aisles);
                int A_ub = (j == N - 1) ? aMax : (int) Math.floor(aMin + (j + 1) * delta_aisles);
                double tUB = upperBounds.getOrDefault(A_lb, ((double) waveSizeUB) / A_lb);
                double tLB = ((double) waveSizeLB) / A_ub;

                result.constraints.add(mc_cplex.addGe(t_hat[j], mc_cplex.prod(tLB, z[j])));
                result.constraints.add(mc_cplex.addLe(t_hat[j], mc_cplex.prod(tUB, z[j])));
                result.constraints.add(mc_cplex.addGe(A_hat[j], mc_cplex.prod(A_lb, z[j])));
                result.constraints.add(mc_cplex.addLe(A_hat[j], mc_cplex.prod(A_ub, z[j])));
            }

            IloLinearNumExpr lb1 = mc_cplex.linearNumExpr();
            IloLinearNumExpr lb2 = mc_cplex.linearNumExpr();
            IloLinearNumExpr ub1 = mc_cplex.linearNumExpr();
            IloLinearNumExpr ub2 = mc_cplex.linearNumExpr();
            
            for (int j = 0; j < N; j++) {
                int A_j_low = (int) Math.floor(aMin + j * delta_aisles);
                int A_j_up = (j == N - 1) ? aMax : (int) Math.floor(aMin + (j + 1) * delta_aisles);
                double tUpperBD = upperBounds.getOrDefault(A_j_low, tUpperBound);
                double tLowerBD = (double) (waveSizeLB / A_j_up);

                lb1.addTerm(A_j_up, t_hat[j]);
                lb1.addTerm(tUpperBD, A_hat[j]);
                lb1.addTerm(-A_j_up * tUpperBD, z[j]);

                lb2.addTerm(A_j_low, t_hat[j]);
                lb2.addTerm(tLowerBD, A_hat[j]);
                lb2.addTerm(-A_j_low * tLowerBD, z[j]);

                ub1.addTerm(A_j_up, t_hat[j]);
                ub1.addTerm(tLowerBD, A_hat[j]);
                ub1.addTerm(-A_j_up * tLowerBD, z[j]);

                ub2.addTerm(A_j_low, t_hat[j]);
                ub2.addTerm(tUpperBD, A_hat[j]);
                ub2.addTerm(-A_j_low * tUpperBD, z[j]);
            }
            
            result.constraints.add(mc_cplex.addGe(w, lb1));
            result.constraints.add(mc_cplex.addGe(w, lb2));
            result.constraints.add(mc_cplex.addLe(w, ub1));
            result.constraints.add(mc_cplex.addLe(w, ub2));

            System.out.println("McCormick constraints: " + (System.currentTimeMillis() - constraintsStart) + "ms");

            // ==================== WARM START ====================

            if (System.currentTimeMillis() - startTime > remainingTime) {
                System.out.println("Breaking warm start due to time limit");
                return result;
            }
            long warmStartStart = System.currentTimeMillis();
            // In solveMcCormickForBranch() method:
            if (useWarmStart && currentBranch != null &&
                currentBranch.parentOrderSolution != null &&
                currentBranch.parentAisleSolution != null &&
                currentBranch.parentObj >= best_obj - 1e-5) {
                
                int A_parent = currentBranch.parentAisleSolution.size();
                double delta = (aMax - aMin) / (double) N;
                int j0 = -1;

                // Find active interval in current branch
                for (int j = 0; j < N; j++) {
                    double lowBound = aMin + j * delta;
                    double highBound = (j == N-1) ? aMax : aMin + (j+1) * delta;
                    if (A_parent >= lowBound && A_parent <= highBound) {
                        j0 = j;
                        break;
                    }
                }
                
                if (j0 != -1) {
                    IloNumVar[] startVars = new IloNumVar[X_mc.length + Y_mc.length + z.length];
                    double[] startVals = new double[startVars.length];
                    int idx = 0;
                    
                    // Orders (X)
                    for (int i = 0; i < X_mc.length; i++) {
                        startVars[idx] = X_mc[i];
                        startVals[idx] = currentBranch.parentOrderSolution.contains(i) ? 1.0 : 0.0;
                        idx++;
                    }
                    
                    // Aisles (Y)
                    for (int j = 0; j < Y_mc.length; j++) {
                        startVars[idx] = Y_mc[j];
                        startVals[idx] = currentBranch.parentAisleSolution.contains(j) ? 1.0 : 0.0;
                        idx++;
                    }
                    
                    // McCormick intervals (Z)
                    for (int j = 0; j < z.length; j++) {
                        startVars[idx] = z[j];
                        startVals[idx] = (j == j0) ? 1.0 : 0.0;
                        idx++;
                    }
                    
                    mc_cplex.addMIPStart(startVars, startVals, IloCplex.MIPStartEffort.Auto);
                }
            }

            // ==================== SOLVE MILP ====================
            long solveStart = System.currentTimeMillis();
            double timeLimitToPass = Math.max(0.1, remainingTime / 1000.0);
            mc_cplex.setParam(IloCplex.Param.TimeLimit, timeLimitToPass);
            mc_cplex.setParam(IloCplex.Param.MIP.Tolerances.MIPGap, mipGapTolerance);
            
            boolean solved = mc_cplex.solve();
            System.out.println("MILP solve time: " + (System.currentTimeMillis() - solveStart) + "ms");
            
            if (solved) {
                result.feasible = true;
                result.optimal = (mc_cplex.getStatus() == IloCplex.Status.Optimal);
                result.selectedOrders = extractSelectedOrders(mc_cplex, X_mc);
                result.selectedAisles = extractSelectedAisles(mc_cplex, Y_mc);
                result.solution = new ChallengeSolution(result.selectedOrders, result.selectedAisles);

                double wVal = mc_cplex.getValue(w);
                double AVal = mc_cplex.getValue(Avar);
                double tVal = mc_cplex.getValue(t);
                result.gap = Math.abs(wVal - (AVal * tVal));
                result.obj = mc_cplex.getValue(t);

                // Find active interval
                for (int j = 0; j < N; j++) {
                    if (mc_cplex.getValue(z[j]) > 0.5) {
                        result.j_selected = j;
                        break;
                    }
                }
            }
        } catch (IloException e) {
            System.err.println("Error in McCormick solution: " + e.getMessage());
        }
        System.out.println("McCormickForBranch total: " + (System.currentTimeMillis() - startTime) + "ms");
        return result;
    }

    private Set<Integer> extractSelectedOrders(IloCplex cplex, IloIntVar[] vars) throws IloException {
        Set<Integer> selected = new HashSet<>();
        for (int i = 0; i < vars.length; i++) {
            if (cplex.getValue(vars[i]) > 0.5) selected.add(i);
        }
        return selected;
    }

    private Set<Integer> extractSelectedAisles(IloCplex cplex, IloIntVar[] vars) throws IloException {
        Set<Integer> selected = new HashSet<>();
        for (int j = 0; j < vars.length; j++) {
            if (cplex.getValue(vars[j]) > 0.5) selected.add(j);
        }
        return selected;
    }

    private void removeMcCormickConstraints(IloCplex mc_cplex, List<IloConstraint> constraints) {
        if (constraints == null) return;
        try {
            for (IloConstraint c : constraints) {
                mc_cplex.remove(c);
            }
        } catch (IloException e) {
            System.err.println("Error removing constraints: " + e.getMessage());
        }
    }

    // ==================== EXISTING METHODS ====================
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