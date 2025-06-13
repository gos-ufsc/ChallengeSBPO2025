
import gurobipy as gp
from gurobipy import GRB

import time

from read import parse_input

example = "datasets/a/instance_0010.txt"
#example = "datasets/b/instance_0012.txt"
parsed_data = parse_input(example)


n_pedidos  = parsed_data['num_orders']
n_itens = parsed_data['num_items']
n_corredores = parsed_data['num_aisles']
LB = parsed_data['LB']
UB = parsed_data['UB']
print("LB = ", LB, end=" ")
print("UB = ", UB)
print("n_pedidos = ", n_pedidos)
print("n_itens = ", n_itens)
print("n_corredores = ", n_corredores)

tempo_total_total = time.time()

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


# Pode ser util para algumas operações como a resolução incial
intens_max_corredores = [0]*n_itens
intens_max_pedidos = [0]*n_itens

for i in range(n_itens):
    for iter in range(n_corredores):
        intens_max_corredores[i] += corredores_itens[iter][i]
    for iter in range(n_pedidos):
        intens_max_pedidos[i] += pedidos_itens[iter][i]


################# MODELO #################
model  =gp.Model()
#variaveis de decisao
pedido_X = model.addVars(n_pedidos, vtype=GRB.BINARY, name="pedido_X")

#funcao objetivo
model.setObjective(gp.quicksum(ordenado_quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)), GRB.MAXIMIZE)

#restricoes
pedidos_fora = set()
corredores_fora = set()
# Infactibilidades # Retirando pedidos que já ultrapassam o UB
for i in range(n_pedidos):
    if ordenado_quantidade_pedidos[i] > UB:
        #model.addConstr(pedido_X[i] == 0)
        #pedido_X[i].Fix(0)
        # É o jeito recomendado pelos stafs do gurobi
        pedido_X[i].LB = 0
        pedido_X[i].UB = 0
        pedidos_fora.add(i)
if len(pedidos_fora) > 0:
        print(f'Pedidos fora > UB: {len(pedidos_fora)}')
# primeira iteração

#quero que a quantidade de itens do pedido_X seja menor ou igual aos corredores corredor_Y selecionados
#lembrando que podemos ter quantidade de pedidos diferentes de quantidade de corredores
temp1 = model.addConstr(gp.quicksum(pedido_X[i] * ordenado_quantidade_pedidos[i] for i in range(n_pedidos))
                <= gp.quicksum(ordenado_quantidade_corredor[i] for i in range(n_corredores)))


#restrição GERAL considerando os itens em cada pedido separadamente
temp2 = []
for itens in range(n_itens):
    temp2.append(model.addConstr(gp.quicksum(pedido_X[i] * pedidos_itens[i][itens] for i in range(n_pedidos) if pedidos_itens[i][itens] > 0) 
                    <= gp.quicksum(corredores_itens[j][itens] for j in range(n_corredores))))

#corredor_Y = model.addVars(n_corredores, vtype=GRB.BINARY, name="corredor_Y")

model.setParam('OutputFlag', 0)  # Desativa os prints
total_temp = time.time()
model.optimize() #Resolver otimizacao considerando todos os corredores com o maximo de pedidos possiveis
print()
print(f'Tempo Modelo 1: {time.time() - total_temp}')

for i in range(n_pedidos):
    if pedido_X[i].x > 0:
        pass
    else:
        if i not in pedidos_fora:
            pedidos_fora.add(i)
            # removendo das proximas solucoes
            pedido_X[i].LB = 0
            pedido_X[i].UB = 0
            
#cont = 1
if len(pedidos_fora) > 0:
    print(f'Pedidos fora Modelo 1: {len(pedidos_fora)}')
    #for i in pedidos_fora:
    #   print(f'Pedido: {i}, {cont}')
    #    cont += 1

model.remove(temp1)
for iter in temp2:
    model.remove(iter)


######## PARTE 2 ########
print()
print("######## PARTE 2 ########")

# Agora posso remodelar considerando os corredores
#var
corredor_Y = model.addVars(n_corredores, vtype=GRB.BINARY, name="corredor_Y")

#constr
model.addConstr(gp.quicksum(pedido_X[i] * ordenado_quantidade_pedidos[i] for i in range(n_pedidos))
                <= gp.quicksum(corredor_Y[i] * ordenado_quantidade_corredor[i] for i in range(n_corredores)))


#restrição GERAL considerando os itens em cada pedido separadamente
for itens in range(n_itens):
    model.addConstr(gp.quicksum(pedido_X[i] * pedidos_itens[i][itens] for i in range(n_pedidos) if pedidos_itens[i][itens] > 0) 
                    <= gp.quicksum(corredor_Y[j] * corredores_itens[j][itens] for j in range(n_corredores)))
#####################
total_temp = time.time()
# inicializacao da parte 2
n_corredores_restantes = n_corredores
conjuntos_match = []
casais_validos = []
casais_invalidos = []
while model.status == GRB.OPTIMAL:
    # na proxima solucao tera no minimo um corredor a menos
    temp3 = model.addConstr(gp.quicksum(corredor_Y[i] for i in range(n_corredores)) <= n_corredores_restantes -1)
    model.optimize()
    if model.status == GRB.OPTIMAL:
        itens_casal = [0]*n_itens 
        valid = True

        #removendo corredores das proximas
        c_array = []
        count_corredores = 0
        for i in range(n_corredores):
            if corredor_Y[i].x > 0:
                count_corredores += 1
            else:
                if i not in corredores_fora:
                    c_array.append(i)
                    corredores_fora.add(i)
                    # removendo das proximas solucoes
                    for iter_itens in range(n_itens): 
                        itens_casal[iter_itens] += corredores_itens[i][iter_itens]
                    corredor_Y[i].LB = 0
                    corredor_Y[i].UB = 0

        #removendo pedidos das proximas
        p_array = []
        pontuacao = 0
        for i in range(n_pedidos):
            if pedido_X[i].x > 0:
                pass
            else:
                if i not in pedidos_fora:
                    p_array.append(i)
                    pedidos_fora.add(i)
                    pontuacao += ordenado_quantidade_pedidos[i]
                    for iter_itens in range(n_itens): 
                        itens_casal[iter_itens] -= pedidos_itens[i][iter_itens]
                        if itens_casal[iter_itens] < 0:
                            valid = False

                        
                    # removendo das proximas solucoes
                    pedido_X[i].LB = 0
                    pedido_X[i].UB = 0
        if pontuacao > UB:
            valid = False
        conjuntos_match.append((p_array, c_array, pontuacao))
        if valid:
            casais_validos.append((p_array, c_array, pontuacao, itens_casal))
            if pontuacao >= LB and pontuacao <= UB and len(p_array)>0:
                print("Validos")
                print(f'A = {len(p_array)} Obj = {pontuacao} Obj/A = {pontuacao/len(p_array)}')
        else:
            casais_invalidos.append((p_array, c_array, pontuacao, itens_casal))
        n_corredores_restantes -= 1
        model.remove(temp3)
        if model.ObjVal >= LB and model.ObjVal <= UB and count_corredores >0:
            print("Restantes")
            print(f'A = {count_corredores} Obj = {model.ObjVal} Obj/A = {model.ObjVal/count_corredores}')
    else:
        break

total_temp = time.time() - total_temp
print("tempo total:", total_temp)
print(f'numero de matchs: {len(conjuntos_match)}')
print(f'numero de matchs validos: {len(casais_validos)}')
print(f'Corredores que ficaram {n_corredores_restantes}')    
print(f'Pedidos que ficaram {n_pedidos - len(pedidos_fora)}')
# Adcicionando os pedidos e corredores que ficaram
p_array = []
for i in range(n_pedidos):
    if i not in pedidos_fora:
        p_array.append(i)
        
c_array = []
for i in range(n_corredores):
    if i not in corredores_fora:
        c_array.append(i)
match = (p_array, c_array)

model2 = gp.Model()
model2.setParam('OutputFlag', 0)  # Desativa os prints 

#vars

casal = model2.addVars(len(casais_validos), vtype=GRB.BINARY, name="casal")
quase_casal = model2.addVars(len(casais_invalidos), vtype=GRB.BINARY, name="quase_casal")

model2.setObjective(gp.quicksum(casais_validos[i][2] * casal[i] for i in range(len(casais_validos)))+
                    gp.quicksum(casais_invalidos[i][2] * quase_casal[i] for i in range(len(casais_invalidos))), GRB.MAXIMIZE)

#constr

model2.addConstr(gp.quicksum(casal[i]*casais_validos[i][2] for i in range(len(casais_validos))) +
                gp.quicksum(quase_casal[i]*casais_invalidos[i][2] for i in range(len(casais_invalidos))) <= UB)
# Removi o lower para ter uma solução de cara
model2.addConstr(gp.quicksum(casal[i]*casais_validos[i][2] for i in range(len(casais_validos))) + 
                gp.quicksum(quase_casal[i]*casais_invalidos[i][2] for i in range(len(casais_invalidos))) >= LB)

for i in range(n_itens):
    model2.addConstr(gp.quicksum(casal[j] * casais_validos[j][3][i] for j in range(len(casais_validos))) +
                    gp.quicksum(quase_casal[j] * casais_invalidos[j][3][i] for j in range(len(casais_invalidos))) >=0)

model2.optimize()

if model2.status == GRB.OPTIMAL:
    contar = 0
    for i in range(len(casais_validos)):
        if casal[i].x > 0:
            contar += len(casais_validos[i][1])
    for i in range(len(casais_invalidos)):
        if quase_casal[i].x > 0:
            contar += len(casais_invalidos[i][1])
    print()
    print("MELHOR SOLUCAO")
    res = model2.ObjVal/contar
    print(f'numero de itens: {model2.ObjVal}')
    print(f'numero de corredores: {contar}')
    print(res)
    print(f"Tempo total da simulacao completa = {time.time() - tempo_total_total}")
else:
    print("Nao tem solucao")
