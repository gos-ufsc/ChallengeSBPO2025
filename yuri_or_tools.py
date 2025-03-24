import time
from ortools.linear_solver import pywraplp
from read import parse_input

# Lê os dados da instância
example = "datasets/a/instance_0020.txt"
parsed_data = parse_input(example)

n_pedidos    = parsed_data['num_orders']
n_itens      = parsed_data['num_items']
n_corredores = parsed_data['num_aisles']
LB           = parsed_data['LB']
UB           = parsed_data['UB']
quantidade_pedidos = parsed_data['soma_pedidos']

# Se não for usar uma seleção reduzida de corredores:
quantidade_corredor = parsed_data['soma_corredor']

best = 0.0
best_A = 0

print("*****INICIO*****")
total_temp = time.time()

# Loop para testar diferentes números de corredores (de 1 até n_corredores)
for a in range(n_corredores):
    # Cria o solver para cada iteração
    solver = pywraplp.Solver.CreateSolver('CBC_MIXED_INTEGER_PROGRAMMING')
    if not solver:
        raise Exception("Solver CBC não está disponível.")
    
    # Criação das variáveis de decisão
    # pedido_X: binária para cada pedido
    pedido_X = [solver.BoolVar(f"pedido_X_{i}") for i in range(n_pedidos)]
    # corredor_Y: binária para cada corredor
    corredor_Y = [solver.BoolVar(f"corredor_Y_{j}") for j in range(n_corredores)]
    
    # Função objetivo: maximizar a soma ponderada dos pedidos
    solver.Maximize(solver.Sum(quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)))
    
    # Restrição 1: soma(pedido_X[i]*quantidade_pedidos[i]) >= LB
    solver.Add(solver.Sum(quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) >= LB)
    
    # Restrição 2: soma(pedido_X[i]*quantidade_pedidos[i]) <= UB
    solver.Add(solver.Sum(quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) <= UB)
    
    # Restrição 3: soma(pedido_X[i]*quantidade_pedidos[i]) <= soma(corredor_Y[j]*quantidade_corredor[j])
    solver.Add(solver.Sum(pedido_X[i] * quantidade_pedidos[i] for i in range(n_pedidos))
               <= solver.Sum(corredor_Y[j] * quantidade_corredor[j] for j in range(n_corredores)))
    
    # Restrição 4: para cada item, os pedidos não podem exceder o disponível nos corredores
    for item in range(n_itens):
        pedido_expr = solver.Sum(
            pedido_X[i] * parsed_data['orders'][i][item]
            for i in range(n_pedidos) if parsed_data['orders'][i][item] > 0
        )
        corredor_expr = solver.Sum(
            corredor_Y[j] * parsed_data['aisles'][j][item]
            for j in range(n_corredores)
        )
        solver.Add(pedido_expr <= corredor_expr)
    
    # Restrição 5 (aceleração): soma(pedido_X[i]) <= n_max_pedidos_UB - 1
    n_max_UB = parsed_data['n_max_pedidos_UB']
    solver.Add(solver.Sum(pedido_X[i] for i in range(n_pedidos)) <= n_max_UB - 1)
    
    # Restrição 6 (aceleração): soma(pedido_X[i]) >= n_min_pedidos_LB
    n_min_LB = parsed_data['n_min_pedidos_LB']
    solver.Add(solver.Sum(pedido_X[i] for i in range(n_pedidos)) >= n_min_LB)
    
    # Restrições temporárias:
    # 1. Forçar que exatamente (a+1) corredores sejam selecionados
    solver.Add(solver.Sum(corredor_Y[j] for j in range(n_corredores)) == a + 1)
    
    # 2. Impor que a soma ponderada dos pedidos seja >= best * (a+1)
    # Na primeira iteração, best é 0 e essa restrição é irrestritiva.
    solver.Add(solver.Sum(quantidade_pedidos[i] * pedido_X[i] for i in range(n_pedidos)) >= best * (a + 1))
    
    # Tempo de resolução para a iteração atual
    t = time.time()
    status = solver.Solve()
    
    if status == pywraplp.Solver.OPTIMAL:
        obj_val = solver.Objective().Value()
        valor_por_corredor = obj_val / (a + 1)
        print(f'Obj: {valor_por_corredor:.4f}  A = {a+1}')
        print("Tempo = %.4f" % (time.time() - t))
        if valor_por_corredor > best:
            best = valor_por_corredor
            best_A = a + 1
    else:
        print(f"Nao tem solucao  A = {a+1} | Tempo = {time.time()-t:.4f}")
    
    # Critério de parada: se a melhor solução já atingir o limite superior esperado, encerra o loop
    if best >= UB / (a + 2):
        print("Nao existe solução melhor")
        break

total_temp = time.time() - total_temp
print("Tempo total:", total_temp)
print("MELHOR SOLUCAO")
print("valor =", best)
print("Corredores =", best_A)
