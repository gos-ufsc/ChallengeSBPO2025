import gurobipy as gp
from gurobipy import GRB

import time

from read import parse_input

example = "datasets/a/instance_0014.txt"
parsed_data = parse_input(example)

model = gp.Model()

n_pedidos = parsed_data['num_orders']
n_itens = parsed_data['num_items']
n_corredores_max = parsed_data['num_aisles']
LB = parsed_data['LB']
UB = parsed_data['UB']

quantidade_pedidos = parsed_data['soma_pedidos']
quantidade_corredor = parsed_data['soma_corredor']

# Variables
pedido_X = model.addVars(n_pedidos, vtype=GRB.BINARY, name="pedido_X")
corredor_Y = model.addVars(n_corredores_max, vtype=GRB.BINARY, name="corredor_Y")
n_corredores = model.addVar(vtype=GRB.INTEGER, name="n_corredores", lb=1, ub=n_corredores_max)

# McCormick Linearization
# We want to maximize sum(quantidade_pedidos[i] * pedido_X[i]) / n_corredores
# Introduce a new variable z = 1/n_corredores
z = model.addVar(name="z", lb=1/n_corredores_max, ub=1, vtype=GRB.CONTINUOUS)

# The constraint z = 1/n_corredores is nonlinear, gonna linearize!
breakpoints_n = list(range(1, n_corredores_max + 1))
# Corresponding z values
breakpoints_z = [1/n for n in breakpoints_n]

# Add SOS2 constraint to model the piecewise linear relationship
# between n_corredores and z
lambda_vars = model.addVars(len(breakpoints_n), name="lambda")
model.addSOS(GRB.SOS_TYPE2, [lambda_vars[i] for i in range(len(breakpoints_n))])
model.addConstr(gp.quicksum(lambda_vars[i] for i in range(len(breakpoints_n))) == 1)
model.addConstr(n_corredores == gp.quicksum(breakpoints_n[i] * lambda_vars[i] for i in range(len(breakpoints_n))))
model.addConstr(z == gp.quicksum(breakpoints_z[i] * lambda_vars[i] for i in range(len(breakpoints_n))))

# Objective function: maximize the ratio by using z (ainda non-linear)
#model.setObjective(gp.quicksum(quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) * z, GRB.MAXIMIZE)

# McCormick Linearization Variables
w = model.addVars(n_pedidos, lb=0, ub=1, name="w")

z_min = 1/n_corredores_max
z_max = 1

# McCormick Envelopes
for i in range(n_pedidos):
    model.addConstr(w[i] <= z_max * pedido_X[i])
    model.addConstr(w[i] >= z_min * pedido_X[i])
    model.addConstr(w[i] <= z - z_min * (1 - pedido_X[i]))
    model.addConstr(w[i] >= z - z_max * (1 - pedido_X[i]))

# Objective (fully linearized)
model.setObjective(gp.quicksum(quantidade_pedidos[i] * w[i] for i in range(n_pedidos)), GRB.MAXIMIZE)

############################################################################

# Constraints
model.addConstr(gp.quicksum(quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) >= LB)
model.addConstr(gp.quicksum(quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) <= UB)
model.addConstr(gp.quicksum(pedido_X[i] * quantidade_pedidos[i] for i in range(n_pedidos)) 
                <= gp.quicksum(corredor_Y[i] * quantidade_corredor[i] for i in range(n_corredores_max)))

# Link n_corredores to corridor selection
model.addConstr(gp.quicksum(corredor_Y[i] for i in range(n_corredores_max)) == n_corredores)

# Item-specific constraints
for itens in range(n_itens):
    model.addConstr(gp.quicksum(pedido_X[i] * parsed_data['orders'][i][itens] for i in range(n_pedidos) if parsed_data['orders'][i][itens] > 0) 
                   <= gp.quicksum(corredor_Y[j] * parsed_data['aisles'][j][itens] for j in range(n_corredores_max)))

# Infeasibility handling
for i in range(n_pedidos):
    if quantidade_pedidos[i] > UB:
        model.addConstr(pedido_X[i] == 0)

# Acceleration constraints
n_max_UB = parsed_data['n_max_pedidos_UB']
print(f'n_max = {n_max_UB}')
model.addConstr(gp.quicksum(pedido_X[i] for i in range(n_pedidos)) <= n_max_UB - 1)

n_min_LB = parsed_data['n_min_pedidos_LB']
print(f'n_min = {n_min_LB}')
model.addConstr(gp.quicksum(pedido_X[i] for i in range(n_pedidos)) >= n_min_LB)

# Setup and solve
model.setParam('OutputFlag', 1)
print("*****INICIO*****")
total_temp = time.time()

model.optimize()

if model.status == GRB.OPTIMAL:
    n_corredores_val = int(round(model.getVarByName("n_corredores").x))
    obj_value = model.objVal
    print('Obj:', obj_value, "A = ", n_corredores_val)
    print('Ratio:', obj_value / n_corredores_val)
    
    # Extract solution
    pedidos = []
    n_pedidos_atendidos = 0
    for i in range(n_pedidos):
        if pedido_X[i].x > 0.5:
            n_pedidos_atendidos += 1
            pedidos.append(i)
    
    corredores = []
    n_corredores_atendidos = 0
    for i in range(n_corredores_max):
        if corredor_Y[i].x > 0.5:
            n_corredores_atendidos += 1
            corredores.append(i)
else:
    print("Nao tem solucao")

total_temp = time.time() - total_temp
print("Tempo total:", total_temp)

print("MELHOR SOLUCAO")
if model.status == GRB.OPTIMAL:
    print("valor = ", obj_value / n_corredores_val)
    print("Corredores = ", n_corredores_val)
    print("OUTPUT ESPERADO")
    print(n_pedidos_atendidos)
    #for i in pedidos:
    #    print(i)
    #print(n_corredores_atendidos)
    #for i in corredores:
    #    print(i)

    if False:
        # Write output file
        output_path = "output.txt"
        with open(output_path, "w") as file:
            file.write(str(n_pedidos_atendidos))
            file.write("\n")
            for i in pedidos:
                file.write(str(i))
                file.write("\n")
            file.write(str(n_corredores_atendidos))
            file.write("\n")
            for i in corredores:
                file.write(str(i))
                file.write("\n")