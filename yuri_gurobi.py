import gurobipy as gp
from gurobipy import GRB

import time

from read import parse_input

example = "datasets/a/instance_0020.txt"
parsed_data = parse_input(example)

model  = gp.Model()

n_pedidos  = parsed_data['num_orders']
n_itens = parsed_data['num_items']
n_corredores = parsed_data['num_aisles']
LB = parsed_data['LB']
UB = parsed_data['UB']


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



#variaveis de decisao
pedido_X = model.addVars(n_pedidos, vtype=GRB.BINARY, name="pedido_X")
corredor_Y = model.addVars(n_corredores, vtype=GRB.BINARY, name="corredor_Y")

# TESTE CONSIDERANDO AS VARIAVEIS BINARIAS COMO CONTINUAS E LB = 0 E UB = 1
#pedido_X = model.addVars(n_pedidos, vtype=GRB.CONTINUOUS, name="pedido_X", lb=0, ub=1)
#corredor_Y = model.addVars(n_corredores, vtype=GRB.CONTINUOUS, name="corredor_Y", lb=0, ub=1)

#funcao objetivo
#vai ser variavel
model.setObjective(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)), GRB.MAXIMIZE)

#restricoes


#quero que a soma de itens dos pedidos seja maior ou igual ao LB
model.addConstr(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) >= LB)

#quero que a soma de itens dos pedidos seja menor ou igual ao UB
model.addConstr(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) <= UB)

#quero que a quantidade de itens do pedido_X seja menor ou igual aos corredores corredor_Y selecionados
#lembrando que podemos ter quantidade de pedidos diferentes de quantidade de corredores
model.addConstr(gp.quicksum(pedido_X[i] * ordenado_quantidade_pedidos[i] for i in range(n_pedidos))
                <= gp.quicksum(corredor_Y[i] * ordenado_quantidade_corredor[i] for i in range(n_corredores)))


#restrição GERAL considerando os itens em cada pedido separadamente
for itens in range(n_itens):
    model.addConstr(gp.quicksum(pedido_X[i] * pedidos_itens[i][itens] for i in range(n_pedidos) if pedidos_itens[i][itens] > 0) 
                    <= gp.quicksum(corredor_Y[j] * corredores_itens[j][itens] for j in range(n_corredores)))
        
        
# Infactibilidades
for i in range(n_pedidos):
    if ordenado_quantidade_pedidos[i] > UB:
        model.addConstr(pedido_X[i] == 0)
        #pedido_X[i].Fix(0)

# Desigualdade valida e auxilio em infactibilidade
#model.addConstr(gp.quicksum(ordenado_quantidade_corredor[i] * corredor_Y[i] for i in range(n_corredores)) >= LB)
        
# aceleração
n_max_UB = parsed_data['n_max_pedidos_UB']
print(f'n_max = {n_max_UB}')
model.addConstr(gp.quicksum(pedido_X[i] for i in range(n_pedidos)) <= n_max_UB - 1)

# NAO USAR ESTA RESTRIÇÃO POR ENQUANTO (precisa coincidir os coeficientes com a ordenação natural dos pedidos)
# Mesma restrição porem mais forte
#coedificientes_multiply = parsed_data['coeficientes_multiply']
#model.addConstr(gp.quicksum(pedido_X[i]*coedificientes_multiply[i] for i in range(n_pedidos)) <= n_max_UB -1)

n_min_LB = parsed_data['n_min_pedidos_LB']
print(f'n_min = {n_min_LB}')
model.addConstr(gp.quicksum(pedido_X[i] for i in range(n_pedidos)) >= n_min_LB)

# Novas Coberturas
#arr_conjuntos_UB = parsed_data['arr_conjuntos_UB']
#for conjunto_n in range(len(arr_conjuntos_UB)): 
#    conjunto = arr_conjuntos_UB[conjunto_n]
#    n = len(conjunto) 
#    if conjunto_n >0:
#        conjunto = conjunto + temp_conjunto
#        if len(conjunto) > n_max_UB:
#            # restrições desnecessárias
#            break
#    print(f'conjunto tamanho {n}')
#    temp_conjunto = conjunto
#    # i é um numero negativo para representar o indice corretamente
#    # i é referente ao array invertido, salvo como: - iter - 1
#    model.addConstr(gp.quicksum(pedido_X[n_pedidos +i] for i in conjunto) <= n -1)


#solucoes = []
#solucoes_dict = {}
best = 0
best_A = 0
melhor_solucao = []
model.setParam('OutputFlag', 0)  # Desativa os prints

print("*****INICIO*****")
total_temp = time.time()
T = 60*10
for a in range(n_corredores):
    #quero que só tenha 1 corredor
    restricao_temporaria = model.addConstr(gp.quicksum(corredor_Y[i] for i in range(n_corredores)) == a+1)
    restricao_temporaria_2 = model.addConstr(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) >= best*(a+1))
    
    t = time.time()
    tempo_restante = T - (time.time() - total_temp)
    if tempo_restante < 0:
        break
    model.setParam('TimeLimit', tempo_restante)
    #model.reset()
    model.optimize()
    if model.status == GRB.OPTIMAL:
        #solucoes.append(model.objVal/(a+1))
        print('Obj:', (model.objVal)/(a+1), "A = ", a +1) 
        print("Tempo = %.4f" % (time.time() - t))
        if model.objVal/(a+1) >= best:
            best = model.objVal/(a+1)
            best_A = a+1

            #PEDIDOS
            pedidos = []
            n_pedidos_atendidos = 0
            for i in range(n_pedidos):
                if pedido_X[i].x == 1:
                    #print(pedidos_itens[i])
                    #pedidos.append(pedidos_itens[i])
                    n_pedidos_atendidos += 1
                    pedidos.append(i)
            
            #CORREDORES
            corredores = []
            n_corredores_atendidos = 0
            for i in range(n_corredores):
                if corredor_Y[i].x == 1:
                    #print(corredores_itens[i])
                    #corredores.append(corredores_itens[i])
                    n_corredores_atendidos += 1
                    corredores.append(i)
            #solucoes_dict[a] = [pedidos, corredores]
            melhor_solucao = [pedidos, corredores]
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


# reescrever solução pelo com a ordem inicial correta usando o ordenado_pedidos e ordenado_corredores


temp = []
for i in pedidos:
    temp.append(ordenado_pedidos[i][0])
    print(ordenado_pedidos[i][0])


temp.sort()
pedidos = temp
temp2 = []
for i in corredores:
    temp2.append(ordenado_corredores[i][0])
temp2.sort()
corredores = temp2


#print somatoria de pedidos selecionados
#res = sum(pedido_X[i].x for i in range(n_pedidos)) #teste para validação
#print("somatoria dos pedidos selecionados", res)
print("MELHOR SOLUCAO")
print("valor = ",  best)
print("Corredores = ", best_A)
print("OUTPUT ESPERADO")
#print("n pedidos atendidos = ", n_pedidos_atendidos)
print(n_pedidos_atendidos)
#for i in pedidos:
#    print(i)
#print("n corredores atendidos = ", n_corredores_atendidos)
#print(n_corredores_atendidos)
#for i in corredores:
#    print(i)

import sys

# Verifica se os argumentos foram informados corretamente
#if len(sys.argv) != 3:
#    print("Uso: python programa.py <input-file> <output-file>")
#    sys.exit(1)
#
#    input_path = sys.argv[1]
#    output_path = sys.argv[2]

output_path = "output.txt"

#criar arquivo de testo com o output esperad
if True:
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