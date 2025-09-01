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

    // print instance size
    public void printInstanceSize() {
        System.out.println("Number of orders: " + orders.size());
        System.out.println("Number of aisles: " + aisles.size());
        System.out.println("Number of items: " + nItems);
        System.out.println("Wave size lower bound: " + waveSizeLB);
        System.out.println("Wave size upper bound: " + waveSizeUB);
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


    public ChallengeSolution solve(StopWatch stopWatch) {
        IloCplex cplex = null;
        try {
            cplex = new IloCplex();

            IloIntVar[] x = cplex.boolVarArray(orders.size());
            IloIntVar[] y = cplex.boolVarArray(aisles.size());

            buildModel(cplex, x, y);
            
            // cplex.setParam(IloCplex.Param.TimeLimit, getRemainingTime(stopWatch));
            cplex.setParam(IloCplex.Param.MIP.Display, 2);

            if (cplex.solve()) {
                System.out.println("Solution found!");
                System.out.println("Objective value: " + cplex.getObjValue());

                Set<Integer> selectedOrders = new HashSet<>();
                Set<Integer> visitedAisles = new HashSet<>();
                
                // Use the variables directly instead of getVarByName
                for(int i = 0; i < orders.size(); i++) {
                    if (cplex.getValue(x[i]) > 0.5) { // Use tolerance for binary vars
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
                System.err.println("No feasible solution found.");
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
        int nOrders = orders.size();
        int nAisles = aisles.size();

        // --- 1. DEFINE NEW VARIABLES for the transformation ---

        // q represents 1 / (number of aisles). Since num_aisles >= 1, 0 <= q <= 1.
        IloNumVar q = cplex.numVar(0, 1.0, "q");

        // Create continuous counterparts for x and y, where y_x = q*x and y_y = q*y
        IloNumVar[] y_x = cplex.numVarArray(nOrders, 0, 1.0);
        IloNumVar[] y_y = cplex.numVarArray(nAisles, 0, 1.0);


        // --- 2. DEFINE THE NEW OBJECTIVE FUNCTION ---
        // The objective Maximize(Numerator/Denominator) becomes Maximize(Transformed Numerator)
        IloLinearNumExpr transformedNumerator = cplex.linearNumExpr();
        for (int i = 0; i < nOrders; i++) {
            for (int units : orders.get(i).values()) {
                transformedNumerator.addTerm(units, y_x[i]);
            }
        }
        cplex.addMaximize(transformedNumerator);


        // --- 3. DEFINE THE NEW DENOMINATOR CONSTRAINT ---
        // The constraint Denominator * q = 1 becomes Transformed Denominator = 1
        IloLinearNumExpr transformedDenominator = cplex.linearNumExpr();
        for (int j = 0; j < nAisles; j++) {
            transformedDenominator.addTerm(1.0, y_y[j]);
        }
        cplex.addEq(transformedDenominator, 1.0);


        // --- 4. TRANSFORM THE ORIGINAL CONSTRAINTS ---

        // Constraint 1: Item availability
        // sum(units_k * x_i) <= sum(units_k * y_j)  ==>  sum(units_k * y_x_i) <= sum(units_k * y_y_j)
        for (int k = 0; k < nItems; k++) {
            IloLinearNumExpr totalUnitsPickedForItem = cplex.linearNumExpr();
            for (int i = 0; i < nOrders; i++) {
                if (orders.get(i).containsKey(k)) {
                    totalUnitsPickedForItem.addTerm(orders.get(i).get(k), y_x[i]);
                }
            }
            IloLinearNumExpr totalUnitsAvailableForItem = cplex.linearNumExpr();
            for (int j = 0; j < nAisles; j++) {
                if (aisles.get(j).containsKey(k)) {
                    totalUnitsAvailableForItem.addTerm(aisles.get(j).get(k), y_y[j]);
                }
            }
            cplex.addLe(totalUnitsPickedForItem, totalUnitsAvailableForItem);
        }

        // Constraint 2: Order-Aisle Linking
        // x_i <= sum(y_j)  ==>  y_x_i <= sum(y_y_j)
        for (int i = 0; i < nOrders; i++) {
            for (int k : orders.get(i).keySet()) {
                IloLinearNumExpr necessaryAisles = cplex.linearNumExpr();
                for (int j = 0; j < nAisles; j++) {
                    if (aisles.get(j).containsKey(k)) {
                        necessaryAisles.addTerm(1.0, y_y[j]);
                    }
                }
                cplex.addLe(y_x[i], necessaryAisles);
            }
        }

        // Constraint 3: Wave size bounds
        // totalUnits >= LB  ==>  transformedNumerator >= LB * q
        cplex.addGe(transformedNumerator, cplex.prod(waveSizeLB, q));
        // totalUnits <= UB  ==>  transformedNumerator <= UB * q
        cplex.addLe(transformedNumerator, cplex.prod(waveSizeUB, q));


        // --- 5. LINK ORIGINAL & TRANSFORMED VARIABLES (Big-M Method) ---
        // Here, the upper bound M for q is 1.0, which is very tight.
        double M_q = 1.0;

        // Add linking constraints for each x_i and its counterpart y_x_i
        for (int i = 0; i < nOrders; i++) {
            // Enforces: if x[i]=0, y_x[i]=0. If x[i]=1, y_x[i]=q
            cplex.addLe(y_x[i], cplex.prod(M_q, x[i]));
            cplex.addLe(y_x[i], q);
            cplex.addGe(y_x[i], cplex.sum(q, cplex.prod(-M_q, cplex.sum(1, cplex.prod(-1, x[i])))));
        }

        // Add linking constraints for each y_j and its counterpart y_y_j
        for (int j = 0; j < nAisles; j++) {
            // Enforces: if y[j]=0, y_y[j]=0. If y[j]=1, y_y[j]=q
            cplex.addLe(y_y[j], cplex.prod(M_q, y[j]));
            cplex.addLe(y_y[j], q);
            cplex.addGe(y_y[j], cplex.sum(q, cplex.prod(-M_q, cplex.sum(1, cplex.prod(-1, y[j])))));
        }
    }

    private void linearizeDenominator(IloCplex cplex, IloLinearNumExpr sum_y, IloNumVar t, IloNumVar w,
                                  IloLinearNumExpr totalUnitsPicked, int base, int nAisles) throws IloException {

        // 1. Calculate P (the number of "digits") needed for the chosen base
        // This is log_base(nAisles)
        int P = (nAisles == 0) ? 1 : (int) Math.floor(Math.log(nAisles) / Math.log(base)) + 1;
        P = 6;

        System.out.println("Base: " + base + ", P: " + P);

        // 2. Create decision variables with sizes dependent on the base
        IloIntVar[][] z = new IloIntVar[P][];
        IloNumVar[][] t_hat = new IloNumVar[P][];
        double M = waveSizeUB; // Your Big-M value

        for (int i = 0; i < P; i++) {
            z[i] = cplex.boolVarArray(base);
            t_hat[i] = cplex.numVarArray(base, 0, M); // Use M as the upper bound
        }

        // 3. Build the expressions for the number and its linearized counterpart
        IloLinearNumExpr sum_aisles_representation = cplex.linearNumExpr(); // Represents sum_y
        IloLinearNumExpr sum_w_representation = cplex.linearNumExpr();      // Represents w

        for (int j = 0; j < P; j++) { // For each digit position
            IloLinearNumExpr sum_z_for_digit = cplex.linearNumExpr();
            IloLinearNumExpr sum_t_hat_for_digit = cplex.linearNumExpr();

            for (int k = 0; k < base; k++) { // For each possible digit value (0 to base-1)
                double placeValue = k * Math.pow(base, j);
                sum_w_representation.addTerm(placeValue, t_hat[j][k]);
                sum_aisles_representation.addTerm(placeValue, z[j][k]);

                cplex.addLe(t_hat[j][k], cplex.prod(M, z[j][k])); // Big-M constraint

                sum_z_for_digit.addTerm(1, z[j][k]);
                sum_t_hat_for_digit.addTerm(1, t_hat[j][k]);
            }
            
            // 4. Add constraints for each digit position
            cplex.addEq(sum_z_for_digit, 1);       // Exactly one digit 'k' must be chosen for this position 'j'
            cplex.addEq(sum_t_hat_for_digit, t); // Links the t_hat variables to the ratio 't'
        }

        // 5. Link the generic representation back to the main model variables
        cplex.addEq(sum_aisles_representation, sum_y);
        cplex.addEq(sum_w_representation, w);
        cplex.addEq(w, totalUnitsPicked);
    }

    protected long getRemainingTime(StopWatch stopWatch) {
        return Math.max(
                TimeUnit.SECONDS.convert(MAX_RUNTIME - stopWatch.getTime(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS),
                0);
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