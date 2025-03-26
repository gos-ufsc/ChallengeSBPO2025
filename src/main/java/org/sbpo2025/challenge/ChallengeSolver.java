package org.sbpo2025.challenge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.StopWatch;

import ilog.concert.*;
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
            int waveSizeUB, 
            List<Integer> soma_pedidos, 
            List<Integer> soma_corredor) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
        this.quantidade_pedidos = soma_pedidos;
        this.quantidade_corredor = soma_corredor;
    }

    public ChallengeSolution solve(StopWatch stopWatch) {
        try {
            IloCplex cplex = new IloCplex();
            // Configurar outputs do CPLEX
            cplex.setParam(IloCplex.Param.Simplex.Display, 0);
            cplex.setParam(IloCplex.Param.MIP.Display, 0);
            cplex.setParam(IloCplex.Param.Tune.Display, 0);
            cplex.setParam(IloCplex.Param.Network.Display, 0);

            // Variáveis de decisão
            IloIntVar[] X = cplex.boolVarArray(orders.size());
            IloIntVar[] Y = cplex.boolVarArray(aisles.size());

            // Função objetivo: Maximizar unidades coletadas
            IloLinearNumExpr objExpr = cplex.linearNumExpr();
            for (int i = 0; i < orders.size(); i++) {
                objExpr.addTerm(quantidade_pedidos.get(i), X[i]);
            }
            cplex.addMaximize(objExpr);

            // Restrição de limite inferior
            IloLinearNumExpr lbExpr = cplex.linearNumExpr();
            for (int i = 0; i < orders.size(); i++) {
                lbExpr.addTerm(quantidade_pedidos.get(i), X[i]);
            }
            cplex.addGe(lbExpr, waveSizeLB);

            // Restrição de limite superior
            IloLinearNumExpr ubExpr = cplex.linearNumExpr();
            for (int i = 0; i < orders.size(); i++) {
                ubExpr.addTerm(quantidade_pedidos.get(i), X[i]);
            }
            cplex.addLe(ubExpr, waveSizeUB);

            // Restrição de capacidade dos corredores
            IloLinearNumExpr capExpr = cplex.linearNumExpr();
            for (int i = 0; i < orders.size(); i++) {
                capExpr.addTerm(quantidade_pedidos.get(i), X[i]);
            }
            for (int j = 0; j < aisles.size(); j++) {
                capExpr.addTerm(-quantidade_corredor.get(j), Y[j]);
            }
            cplex.addLe(capExpr, 0);

            // Restrições por item
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
                
                if (itemExpr.size() > 0) {
                    cplex.addLe(itemExpr, 0);
                }
            }

            // Fixar pedidos inviáveis
            for (int i = 0; i < orders.size(); i++) {
                if (quantidade_pedidos.get(i) > waveSizeUB) {
                    cplex.addEq(X[i], 0);
                }
            }

            // Restrições de aceleração
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

            // Restrição UB aceleração
            IloLinearNumExpr ubAccelExpr = cplex.linearNumExpr();
            for (IloIntVar x : X) ubAccelExpr.addTerm(1, x);
            cplex.addLe(ubAccelExpr, n_max_UB - 1);

            // Restrição LB aceleração
            IloLinearNumExpr lbAccelExpr = cplex.linearNumExpr();
            for (IloIntVar x : X) lbAccelExpr.addTerm(1, x);
            cplex.addGe(lbAccelExpr, n_min_LB);

            // Busca pela melhor solução
            double bestRatio = 0.0;
            Set<Integer> bestOrders = new HashSet<>();
            Set<Integer> bestAisles = new HashSet<>();

            for (int a = 0; a < aisles.size(); a++) {
                if (getRemainingTime(stopWatch) <= 0) break;

                int numAisles = a + 1;
                
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
                    solved = cplex.solve();
                } catch (IloException e) {
                    System.err.println("CPLEX Error: " + e.getMessage());
                }

                if (solved && cplex.getStatus() == IloCplex.Status.Optimal) {
                    double objVal = cplex.getObjValue();
                    double currentRatio = objVal / numAisles;

                    if (currentRatio > bestRatio) {
                        bestRatio = currentRatio;
                        
                        // Extrair solução
                        Set<Integer> selectedOrders = new HashSet<>();
                        for (int i = 0; i < X.length; i++) {
                            try {
                                if (cplex.getValue(X[i]) > 0.9) {
                                    selectedOrders.add(i);
                                }
                            } catch (IloException e) {
                                System.err.println("Error getting X value: " + e.getMessage());
                            }
                        }

                        Set<Integer> selectedAisles = new HashSet<>();
                        for (int j = 0; j < Y.length; j++) {
                            try {
                                if (cplex.getValue(Y[j]) > 0.9) {
                                    selectedAisles.add(j);
                                }
                            } catch (IloException e) {
                                System.err.println("Error getting Y value: " + e.getMessage());
                            }
                        }

                        // Verificar viabilidade
                        ChallengeSolution solution = new ChallengeSolution(selectedOrders, selectedAisles);
                        if (isSolutionFeasible(solution)) {
                            bestOrders = selectedOrders;
                            bestAisles = selectedAisles;
                        }
                    }
                }

                // Remover restrições temporárias
                cplex.remove(sumYConstr);
                cplex.remove(objBoundConstr);

                // Critério de parada
                if (bestRatio >= (waveSizeUB / (numAisles + 1.0))) {
                    break;
                }
            }

            cplex.end();
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
