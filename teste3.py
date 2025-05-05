import gurobipy as gp
from gurobipy import GRB

import time

from read import parse_input

example = "datasets/a/instance_0010.txt"
#example = "datasets/b/instance_0006.txt"
parsed_data = parse_input(example)


n_pedidos  = parsed_data['num_orders']
n_itens = parsed_data['num_items']
n_corredores = parsed_data['num_aisles']
LB = parsed_data['LB']
UB = parsed_data['UB']
print("n_pedidos = ", n_pedidos)
print("n_itens = ", n_itens)
print("n_corredores = ", n_corredores)
print("UB = ", UB)
print("LB = ", LB)


quantidade_pedidos = []
quantidade_corredor = []
for pedidos_iter in range(n_pedidos):
    soma = sum(parsed_data['orders'][pedidos_iter])
    #if soma > UB:
    #    #orders_matrix.drop(pedidos_iter)
    #    o -= 1
    #else:
    #    temp_matrix.append(orders_matrix[pedidos_iter])
    #    soma_pedidos.append(soma)
    quantidade_pedidos.append(soma)

#orders_matrix = temp_matrix
for corredor in parsed_data['aisles']:
    quantidade_corredor.append(sum(corredor))

#enumerate crescente
ordenado_pedidos = sorted(enumerate(quantidade_pedidos), key=lambda x: x[1])
# = [indice_anterior, quantidade]
ordenado_corredores = sorted(enumerate(quantidade_corredor), key=lambda x: x[1])

pedidos_itens = [parsed_data['orders'][i] for i, j in ordenado_pedidos]
corredores_itens = [parsed_data['aisles'][i] for i, j in ordenado_corredores]

# sai mais rapido que fazer outro sort
ordenado_quantidade_pedidos = [j for i, j in ordenado_pedidos]
ordenado_quantidade_corredor = [j for i, j in ordenado_corredores]

intens_max_corredores = [0]*n_itens
intens_max_pedidos = [0]*n_itens

for i in range(n_itens):
    for iter in range(n_corredores):
        intens_max_corredores[i] += corredores_itens[iter][i]
    for iter in range(n_pedidos):
        intens_max_pedidos[i] += pedidos_itens[iter][i]



def model_1():
    model_pedido  = gp.Model()
    model_pedido.setParam('OutputFlag', 0)  # Desativa os prints
    #variaveis de decisao
    pedido_X = model_pedido.addVars(n_pedidos, vtype=GRB.BINARY, name="pedido_X")
    #funcao objetivo
    model_pedido.setObjective(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)), GRB.MAXIMIZE)

    #restricoes
    #quero que a soma de itens dos pedidos seja maior ou igual ao LB
    model_pedido.addConstr(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) >= LB)

    #quero que a soma de itens dos pedidos seja menor ou igual ao UB
    model_pedido.addConstr(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) <= UB)

    # factivel: Nao pegar uma combinação que nem o maximo de corredores podem atender
    for iter in range(n_itens):
        model_pedido.addConstr(gp.quicksum(pedido_X[i] * pedidos_itens[i][iter] for i in range(n_pedidos)) <= intens_max_corredores[iter])

    

    model_pedido.optimize()
    print("Pedidos OK")

    itens = [0]*n_itens
    for i in range(n_pedidos):
        if pedido_X[i].x > 0:
            for j in range(n_itens):
                itens[j] += pedidos_itens[i][j]
    soma = sum(itens)

    best = model_pedido.objVal

    #### CORREDOR MINIMO PARA ESSA CONFIGURACAO
    model_corredor = gp.Model()
    model_corredor.setParam('OutputFlag', 0)  # Desativa os prints

    corredor_Y = model_corredor.addVars(n_corredores, vtype=GRB.BINARY, name="corredor_Y")
    model_corredor.setObjective(gp.quicksum(corredor_Y[i] for i in range(n_corredores)), GRB.MINIMIZE)

    model_corredor.addConstr(gp.quicksum(corredor_Y[i] * ordenado_quantidade_corredor[i] for i in range(n_corredores)) >= soma)

    #restrição GERAL considerando os itens em cada pedido separadamente
    for i in range(n_itens):
        model_corredor.addConstr(gp.quicksum(corredor_Y[j] * corredores_itens[j][i] for j in range(n_corredores))>= itens[i])


    model_corredor.optimize()
    corredores = 0
    for i in range(n_corredores):
        if corredor_Y[i].x > 0:
            corredores += 1

    print("Soma:", soma)
    print("Corredores:", corredores)
    print("Best:", soma/corredores)
    return corredores


# da para ir testando isso iteraticamente
def model_2():
    model_pedido  = gp.Model()
    model_pedido.setParam('OutputFlag', 0)  # Desativa os prints
    #variaveis de decisao
    pedido_X = model_pedido.addVars(n_pedidos, vtype=GRB.BINARY, name="pedido_X")
    count_var = model_pedido.addVars(n_itens, vtype=GRB.CONTINUOUS, name="count_var", lb=0, ub=1)

    #funcao objetivo
    model_pedido.setObjective(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos))
                                          -2*(n_itens/UB)* gp.quicksum(count_var[i] for i in range(n_itens)), GRB.MAXIMIZE)
                                          

    #restricoes
    #quero que a soma de itens dos pedidos seja maior ou igual ao LB
    model_pedido.addConstr(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) >= LB)

    #quero que a soma de itens dos pedidos seja menor ou igual ao UB
    model_pedido.addConstr(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) <= UB)

    # factivel: Nao pegar uma combinação que nem o maximo de corredores podem atender
    for iter in range(n_itens):
        model_pedido.addConstr(gp.quicksum(pedido_X[i] * pedidos_itens[i][iter] for i in range(n_pedidos)) <= intens_max_corredores[iter])

    for iter in range(n_itens):
        for pedido in range(n_pedidos):
            if pedidos_itens[pedido][iter] > 0:
                model_pedido.addConstr(count_var[iter] >= pedido_X[pedido])

    

    model_pedido.optimize()
    print("Pedidos OK")

    itens = [0]*n_itens
    for i in range(n_pedidos):
        if pedido_X[i].x > 0:
            for j in range(n_itens):
                itens[j] += pedidos_itens[i][j]
    soma = sum(itens)
    best = model_pedido.objVal

    #### CORREDOR MINIMO PARA ESSA CONFIGURACAO
    model_corredor = gp.Model()
    model_corredor.setParam('OutputFlag', 0)  # Desativa os prints

    corredor_Y = model_corredor.addVars(n_corredores, vtype=GRB.BINARY, name="corredor_Y")
    model_corredor.setObjective(gp.quicksum(corredor_Y[i] for i in range(n_corredores)), GRB.MINIMIZE)

    model_corredor.addConstr(gp.quicksum(corredor_Y[i] * ordenado_quantidade_corredor[i] for i in range(n_corredores)) >= soma)

    #restrição GERAL considerando os itens em cada pedido separadamente
    for i in range(n_itens):
        model_corredor.addConstr(gp.quicksum(corredor_Y[j] * corredores_itens[j][i] for j in range(n_corredores))>= itens[i])


    model_corredor.optimize()
    corredores = 0
    for i in range(n_corredores):
        if corredor_Y[i].x > 0:
            corredores += 1

    print("Soma:", soma)
    print("Corredores:", corredores)
    print("Best:", soma/corredores)
    return corredores

def model_3():
    model_pedido  = gp.Model()
    model_pedido.setParam('OutputFlag', 0)  # Desativa os prints
    #variaveis de decisao
    pedido_X = model_pedido.addVars(n_pedidos, vtype=GRB.BINARY, name="pedido_X")
    count_var = model_pedido.addVars(n_itens, vtype=GRB.CONTINUOUS, name="count_var", lb=0, ub=1)

    #funcao objetivo
    model_pedido.setObjective(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos))
                                          + gp.quicksum(count_var[i] for i in range(n_itens)), GRB.MAXIMIZE)
                                          

    #restricoes
    #quero que a soma de itens dos pedidos seja maior ou igual ao LB
    model_pedido.addConstr(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) >= LB)

    #quero que a soma de itens dos pedidos seja menor ou igual ao UB
    model_pedido.addConstr(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) <= UB)

    # factivel: Nao pegar uma combinação que nem o maximo de corredores podem atender
    for iter in range(n_itens):
        model_pedido.addConstr(gp.quicksum(pedido_X[i] * pedidos_itens[i][iter] for i in range(n_pedidos)) <= intens_max_corredores[iter])

    for iter in range(n_itens):
        for pedido in range(n_pedidos):
            if pedidos_itens[pedido][iter] > 0:
                model_pedido.addConstr(count_var[iter] >= pedido_X[pedido])

    

    model_pedido.optimize()
    print("Pedidos OK")

    itens = [0]*n_itens
    for i in range(n_pedidos):
        if pedido_X[i].x > 0:
            for j in range(n_itens):
                itens[j] += pedidos_itens[i][j]
    soma = sum(itens)

    #### CORREDOR MINIMO PARA ESSA CONFIGURACAO
    model_corredor = gp.Model()
    model_corredor.setParam('OutputFlag', 0)  # Desativa os prints

    corredor_Y = model_corredor.addVars(n_corredores, vtype=GRB.BINARY, name="corredor_Y")
    model_corredor.setObjective(gp.quicksum(corredor_Y[i] for i in range(n_corredores)), GRB.MINIMIZE)

    model_corredor.addConstr(gp.quicksum(corredor_Y[i] * ordenado_quantidade_corredor[i] for i in range(n_corredores)) >= soma)

    #restrição GERAL considerando os itens em cada pedido separadamente
    for i in range(n_itens):
        model_corredor.addConstr(gp.quicksum(corredor_Y[j] * corredores_itens[j][i] for j in range(n_corredores))>= itens[i])


    model_corredor.optimize()
    corredores = 0
    for i in range(n_corredores):
        if corredor_Y[i].x > 0:
            corredores += 1

    print("Corredores:", corredores)
    return corredores
total_temp = time.time()
print("Começou modelo 1")
model_1()
total_temp = time.time() - total_temp
print("Tempo total Model 1:", total_temp)
print()

total_temp = time.time()
print("Começou modelo 2")
model_2()
total_temp = time.time() - total_temp
print("Tempo total Model 2:", total_temp)
print()