package org.sbpo2025.challenge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.StopWatch;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.cplex.IloCplex;

public class ChallengeSolver {
    private final long MAX_RUNTIME = 600000; // milliseconds; 10 minutes

    protected List<Map<Integer, Integer>> orders;
    protected List<Map<Integer, Integer>> aisles;
    protected int nItems;
    protected int waveSizeLB;
    protected int waveSizeUB;
    protected List<Integer> quantidade_pedidos;
    protected List<Integer> quantidade_corredor;

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

    public ChallengeSolution solve(StopWatch stopWatch) {
        try {
            long startTime=System.currentTimeMillis();
            IloCplex cplex = new IloCplex();
            cplex.setOut(null);      // Suppress standard output
            cplex.setWarning(null);  // Suppress warning messages
            // Configurar outputs do CPLEX
            cplex.setParam(IloCplex.Param.Simplex.Display, 0);
            cplex.setParam(IloCplex.Param.MIP.Display, 0);
            cplex.setParam(IloCplex.Param.Tune.Display, 0);
            cplex.setParam(IloCplex.Param.Network.Display, 0);

            // DECISION VARIABLES
            IloIntVar[] X = cplex.boolVarArray(orders.size());
            IloIntVar[] Y = cplex.boolVarArray(aisles.size());

            // OBJECTIVE FUNCTION
            IloLinearNumExpr objExpr = cplex.linearNumExpr();
            for (int i = 0; i < orders.size(); i++) {
                objExpr.addTerm(quantidade_pedidos.get(i), X[i]);
            }
            cplex.addMaximize(objExpr);

             // LOWER BOUND CONSTRAINT
             IloLinearNumExpr lbExpr = cplex.linearNumExpr();
             for (int i = 0; i < orders.size(); i++) {
                 lbExpr.addTerm(quantidade_pedidos.get(i), X[i]);
             }
             cplex.addGe(lbExpr, waveSizeLB);

             // UPPER BOUND CONSTRAINT
             IloLinearNumExpr ubExpr = cplex.linearNumExpr();
             for (int i = 0; i < orders.size(); i++) {
                 ubExpr.addTerm(quantidade_pedidos.get(i), X[i]);
             }
             cplex.addLe(ubExpr, waveSizeUB);

             // PER ITEM CONSTRAINT
             for (int item = 0; item < nItems; item++) {
                 IloLinearNumExpr itemExpr = cplex.linearNumExpr();

                 // Coletar pedidos com o item
                 for (int i = 0; i < orders.size(); i++) {
                     Integer qty = orders.get(i).get(item);
                     if (qty != null && qty > 0) {
                         itemExpr.addTerm(qty, X[i]);
                     }
                 }

                 // Coletar corredores com o item
                 for (int j = 0; j < aisles.size(); j++) {
                    Integer qtyAisle = aisles.get(j).get(item);
                     if (qtyAisle != null && qtyAisle > 0) {
                         itemExpr.addTerm(-qtyAisle, Y[j]);
                     }
                 }

                 cplex.addLe(itemExpr, 0);
             }

             // Aisle capacity
             IloLinearNumExpr capExpr = cplex.linearNumExpr();
             for (int i = 0; i < orders.size(); i++) {
                 capExpr.addTerm(quantidade_pedidos.get(i), X[i]);
             }
             for (int j = 0; j < aisles.size(); j++) {
                 capExpr.addTerm(-quantidade_corredor.get(j), Y[j]);
             }
             cplex.addLe(capExpr, 0);

            // INOQUOUS BUT SAVES TIME

             // Fixar pedidos inviáveis
             for (int i = 0; i < orders.size(); i++) {
                 if (quantidade_pedidos.get(i) > waveSizeUB) {
                     cplex.addEq(X[i], 0);
                 }
             }


            // Restrições de aceleração
             //List<Integer> sortedPedidosDesc = new ArrayList<>(quantidade_pedidos);
             //sortedPedidosDesc.sort(Collections.reverseOrder());
             //int sumLB = 0;
             //int n_min_LB = 0;
             //for (int q : sortedPedidosDesc) {
             //    sumLB += q;
             //    n_min_LB++;
             //    if (sumLB >= waveSizeLB) break;
             //}

             //List<Integer> sortedPedidosAsc = new ArrayList<>(quantidade_pedidos);
             //sortedPedidosAsc.sort(Comparator.naturalOrder());
             //int sumUB = 0;
             //int n_max_UB = 0;
             //for (int q : sortedPedidosAsc) {
             //    if (sumUB + q > waveSizeUB) break;
             //    sumUB += q;
             //    n_max_UB++;
             //}

            // Restrição UB aceleração
            // IloLinearNumExpr ubAccelExpr = cplex.linearNumExpr();
            // for (IloIntVar x : X) ubAccelExpr.addTerm(1, x);
            // cplex.addLe(ubAccelExpr, n_max_UB - 1);

            // Restrição LB aceleração
            // IloLinearNumExpr lbAccelExpr = cplex.linearNumExpr();
            // for (IloIntVar x : X) lbAccelExpr.addTerm(1, x);
            // cplex.addGe(lbAccelExpr, n_min_LB);

            // Busca pela melhor solução
            double bestRatio = 0.0;
            Set<Integer> bestOrders = new HashSet<>();
            Set<Integer> bestAisles = new HashSet<>();

            // tempo maximo de 10 minutos
            float time_limit = 60*10 - 10; // segundos 9 min e 50 segundos (tempo de sobra para garantir entrega)
            // consideramos 10 segundos de sobra
            float tempo_restante = 0;
            
            boolean reversed_mode_loop = false;
            // int tempo faltando
            int time_left = 60*2 + 20; // 2 min e 20 segundos para acabar o tempo vai entrar em acao o modo reverso

            int before = 0; // no loob reverso nao passar de onde foi revertido
            int a = -1; // Detalhe importante, já q o incremento ocorre no inicio
            for (; a < aisles.size();) {
                if (reversed_mode_loop) {
                    a--;
                    if (a <= before) {
                        break;
                    }
                }
                else{
                    a++;}

                long elapsed = (System.currentTimeMillis() - startTime)/1000; // em segundos

                //time limite ja considera menos 10 segundos
                tempo_restante = (int) (time_limit - elapsed);
                
                //                         
                if ((tempo_restante < time_left) && !reversed_mode_loop) {
                    // dovisao inteira
                    before = a;
                    a = (int) aisles.size()/2; 
                    reversed_mode_loop = true;

                }

                if (tempo_restante < 0) {
                    System.out.println("⚠️ Time limit exceeded (" + (System.currentTimeMillis()- startTime) + " milis). Returning best solution found so far.");
                    break;
                }
                if (reversed_mode_loop) {
                    //time limite ja considera menos 10 segundos
                    cplex.setParam(IloCplex.Param.TimeLimit, tempo_restante);
                }
                else{
                    //time limite ja considera menos 10 segundos
                    //cplex.setParam(IloCplex.Param.TimeLimit, tempo_restante - time_left);
                    cplex.setParam(IloCplex.Param.TimeLimit, tempo_restante);
                }

                int numAisles = a + 1;
                //System.out.println("\n === Trying with numAisles = " + numAisles + " ===");


                // Restrições temporárias
                IloLinearNumExpr sumYExpr = cplex.linearNumExpr();
                for (IloIntVar y : Y) sumYExpr.addTerm(1, y);
                IloConstraint sumYConstr = cplex.eq(sumYExpr, numAisles);
                cplex.add(sumYConstr);

                IloLinearNumExpr objBoundExpr = cplex.linearNumExpr();
                for (int i = 0; i < X.length; i++) {
                    objBoundExpr.addTerm(quantidade_pedidos.get(i), X[i]);
                }
                IloConstraint objBoundConstr = cplex.ge(objBoundExpr, bestRatio * numAisles);
                cplex.add(objBoundConstr);

                // Resolver
                boolean solved = false;
                try {
                    //cplex.setWarning(System.out); // show warnings from CPLEX
                    cplex.exportModel("./debug_model.lp"); // save model to file
                    //System.out.println("Exporting model...");
                    solved = cplex.solve();
                } catch (IloException e) {
                    System.err.println("CPLEX Error: " + e.getMessage());
                }

                if (solved && cplex.getStatus() == IloCplex.Status.Optimal || cplex.getStatus() == IloCplex.Status.Feasible) {
                    double objVal = cplex.getObjValue();
                    double currentRatio = objVal / numAisles;
                    //System.out.println("Solver status:"+cplex.getStatus());
                    //System.out.println("Objetive value:"+objVal);
                    //System.out.println("Final objective (units per aisle):"+(objVal/numAisles));

                    if (currentRatio >= bestRatio) {
                        bestRatio = currentRatio;

                        // Extrair solução
                        Set<Integer> selectedOrders = new HashSet<>();
                        //System.out.println("Selected orders:");
                        for (int i = 0; i < X.length; i++) {
                            double val = cplex.getValue(X[i]);
                            //System.out.printf("X[%d]=%.3f%n",i,val);
                            if (val > 0.9) {
                                    selectedOrders.add(i);
                            }
                        }

                        Set<Integer> selectedAisles = new HashSet<>();
                        //System.out.println("Selected aisles:");
                        for (int j = 0; j < Y.length; j++) {
                            double val=cplex.getValue(Y[j]);
                            //System.out.printf("Y[%d]=%.3f%n",j,val);
                            if (val>0.9) {
                                selectedAisles.add(j);
                            }
                        }

                        // Verificar viabilidade
                        ChallengeSolution solution = new ChallengeSolution(selectedOrders, selectedAisles);
                        if (isSolutionFeasible(solution)) {
                            bestOrders = selectedOrders;
                            bestAisles = selectedAisles;
                        } else {
                        //System.out.println("✖ Infeasible solution found for numAisles = " + numAisles + " — discarded");
                        }
                    }
                }
                if (!solved || cplex.getStatus() ==IloCplex.Status.Infeasible || cplex.getStatus() ==IloCplex.Status.Unbounded ) {
                    //System.out.println("No feasible or bounded solution found for numAisles = " + numAisles);
                }
                // Remover restrições temporárias
                cplex.remove(sumYConstr);
                cplex.remove(objBoundConstr);

                // Critério de parada                               nao pode estar no modo reverso tambem
                if (bestRatio >= (waveSizeUB / (numAisles + 1.0)) && !reversed_mode_loop) {
                    //System.out.println("\n============\n No more aisles will be tried: it's not possible to find a better solution with more aisles\n============");
                    break;
                }
            }

            cplex.end();

            System.out.println("\n==== Final Solution ====");
            System.out.printf("Objective (number of items): %.2f%n", bestRatio * bestAisles.size());
            System.out.println("Best final number of orders: " + bestOrders.size());
            System.out.println("Number of aisles: " + bestAisles.size());


            if (bestAisles.size() > 0) {
                System.out.printf("General objective (items per aisle): %.2f%n", bestRatio);
            } else {
                System.out.println("General objective (items per aisle): undefined (no aisles selected)");
            }
            System.out.println("====================\n");
            //System.out.println("\n Best final solution: " + bestOrders.size() + " orders, " + bestAisles.size() + " aisles");
            long totalTime = System.currentTimeMillis() - startTime;
            System.out.println("Total execution time: " + totalTime + " ms");
            return new ChallengeSolution(bestOrders, bestAisles);
        } catch (IloException e) {
            System.err.println("CPLEX Exception: " + e.getMessage());
            return new ChallengeSolution(Collections.emptySet(), Collections.emptySet());
        }
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
