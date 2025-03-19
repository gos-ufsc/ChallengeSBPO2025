import gurobipy as gp
from gurobipy import GRB

import time

from read import parse_input

example = "datasets/a/instance_0014.txt"
parsed_data = parse_input(example)

model  = gp.Model()

n_pedidos  = parsed_data['num_orders']
n_itens = parsed_data['num_items']
n_corredores = parsed_data['num_aisles']
LB = parsed_data['LB']
UB = parsed_data['UB']

quantidade_pedidos = parsed_data['soma_pedidos']

# Seria para considerar uma quantidade menor de corredores
# False -> Estou desconsiderando isso e deixando o problema completo
if False:
    from read import best_n_corredores
    # resolver com 90% dos "melhores" corredores
    nnn = int(0.9*n_corredores)
    if n_corredores > nnn:
        n_corredores = nnn
        parsed_data['aisles'], indices_anteriores, quantidade_corredor = best_n_corredores(parsed_data,n_corredores)
    else:
        quantidade_corredor = parsed_data['soma_corredor']
else:
    quantidade_corredor = parsed_data['soma_corredor']

#variaveis de decisao
pedido_X = model.addVars(n_pedidos, vtype=GRB.BINARY, name="pedido_X")
corredor_Y = model.addVars(n_corredores, vtype=GRB.BINARY, name="corredor_Y")

# TESTE CONSIDERANDO AS VARIAVEIS BINARIAS COMO CONTINUAS E LB = 0 E UB = 1
#pedido_X = model.addVars(n_pedidos, vtype=GRB.CONTINUOUS, name="pedido_X", lb=0, ub=1)
#corredor_Y = model.addVars(n_corredores, vtype=GRB.CONTINUOUS, name="corredor_Y", lb=0, ub=1)

#funcao objetivo
#vai ser variavel
model.setObjective(gp.quicksum(quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)), GRB.MAXIMIZE)

#restricoes


#quero que a soma de itens dos pedidos seja maior ou igual ao LB
model.addConstr(gp.quicksum(quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) >= LB)

#quero que a soma de itens dos pedidos seja menor ou igual ao UB
model.addConstr(gp.quicksum(quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) <= UB)

#quero que a quantidade de itens do pedido_X seja menor ou igual aos corredores corredor_Y selecionados
#lembrando que podemos ter quantidade de pedidos diferentes de quantidade de corredores
model.addConstr(gp.quicksum(pedido_X[i] * quantidade_pedidos[i] for i in range(n_pedidos))
                <= gp.quicksum(corredor_Y[i] * quantidade_corredor[i] for i in range(n_corredores)))


#restrição GERAL considerando os itens em cada pedido separadamente
for itens in range(n_itens):
    model.addConstr(gp.quicksum(pedido_X[i] * parsed_data['orders'][i][itens] for i in range(n_pedidos) if parsed_data['orders'][i][itens] > 0) 
                    <= gp.quicksum(corredor_Y[j] * parsed_data['aisles'][j][itens] for j in range(n_corredores)))
        
        


# Desigualdade valida e auxilio em infactibilidade
#model.addConstr(gp.quicksum(quantidade_corredor[i] * corredor_Y[i] for i in range(n_corredores)) >= LB)
        
# aceleração
n_max_UB = parsed_data['n_max_pedidos_UB']
print(f'n_max = {n_max_UB}')
model.addConstr(gp.quicksum(pedido_X[i] for i in range(n_pedidos)) <= n_max_UB)


#solucoes = []
#solucoes_dict = {}
best = 0
best_A = 0
melhor_solucao = []
model.setParam('OutputFlag', 0)  # Desativa os prints

print("*****INICIO*****")
total_temp = time.time()
for a in range(n_corredores):
    #quero que só tenha 1 corredor
    restricao_temporaria = model.addConstr(gp.quicksum(corredor_Y[i] for i in range(n_corredores)) == a+1)
    restricao_temporaria_2 = model.addConstr(gp.quicksum(quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) >= best*(a+1))
    
    t = time.time()

    #model.reset()
    model.optimize()
    if model.status == GRB.OPTIMAL:
        #solucoes.append(model.objVal/(a+1))
        print('Obj:', (model.objVal)/(a+1), "A = ", a +1) 
        print("Tempo = %.4f" % (time.time() - t))
        if model.objVal/(a+1) > best:
            best = model.objVal/(a+1)
            best_A = a+1
            #pedidos = []
            #print("Pedidos")
            #for i in range(n_pedidos):
            #    if pedido_X[i].x == 1:
                    #print(parsed_data['orders'][i])
            #        pedidos.append(parsed_data['orders'][i])

            #corredores = []
            #print("Corredores")
            #for i in range(n_corredores):
            #    if corredor_Y[i].x == 1:
            #        #print(parsed_data['aisles'][i])
            #        corredores.append(parsed_data['aisles'][i])
            #solucoes_dict[a] = [pedidos, corredores]
            #melhor_solucao = [pedidos, corredores]
    else:
        print("Nao tem solucao", "A = ", a + 1, end=" | ")
        print("Tempo = %.4f" % (time.time() - t))
        #solucoes.append(0)
    model.remove(restricao_temporaria)
    model.remove(restricao_temporaria_2)

    # Sei que não existe soluções melhores
    if best >= UB/(a+2):
        print("Nao existe solução melhor")
        break

total_temp = time.time() - total_temp
print("Tempo total:", total_temp)

#print somatoria de pedidos selecionados
#res = sum(pedido_X[i].x for i in range(n_pedidos)) #teste para validação
#print("somatoria dos pedidos selecionados", res)
print("MELHOR SOLUCAO")
print("valor = ",  best)
print("Corredores = ", best_A)
if False:
    print("PEDIDOS")
    for i in melhor_solucao[0]:
        print(i)
    print("CORREDORES")
    for i in melhor_solucao[1]:
        print(i)
