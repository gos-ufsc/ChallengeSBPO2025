import cplex
from cplex.exceptions import CplexError
import time

from read import parse_input

example = "datasets/a/instance_0020.txt"
parsed_data = parse_input(example)

model = cplex.Cplex()

n_pedidos = parsed_data['num_orders']
n_itens = parsed_data['num_items']
n_corredores = parsed_data['num_aisles']
LB = parsed_data['LB']
UB = parsed_data['UB']

quantidade_pedidos = parsed_data['soma_pedidos']
quantidade_corredor = parsed_data['soma_corredor']

# Desativa saídas do CPLEX
model.set_log_stream(None)
model.set_results_stream(None)
model.set_error_stream(None)
model.set_warning_stream(None)

# Adiciona variáveis
current_num = model.variables.get_num()
model.variables.add(
    types=[model.variables.type.binary] * n_pedidos,
    names=[f"pedido_X_{i}" for i in range(n_pedidos)]
)
pedido_X_indices = list(range(current_num, current_num + n_pedidos))

current_num = model.variables.get_num()
model.variables.add(
    types=[model.variables.type.binary] * n_corredores,
    names=[f"corredor_Y_{j}" for j in range(n_corredores)]
)
corredor_Y_indices = list(range(current_num, current_num + n_corredores))

# Função objetivo
model.objective.set_sense(model.objective.sense.maximize)
obj = [(i, quantidade_pedidos[idx]) for idx, i in enumerate(pedido_X_indices)]
model.objective.set_linear(obj)

# Restrições
# Restrição LB
model.linear_constraints.add(
    lin_expr=[cplex.SparsePair(pedido_X_indices, quantidade_pedidos)],
    senses=["G"],
    rhs=[LB],
    names=["LB_constraint"]
)

# Restrição UB
model.linear_constraints.add(
    lin_expr=[cplex.SparsePair(pedido_X_indices, quantidade_pedidos)],
    senses=["L"],
    rhs=[UB],
    names=["UB_constraint"]
)

# Restrição de capacidade dos corredores
combined_indices = pedido_X_indices + corredor_Y_indices
combined_values = quantidade_pedidos + [-q for q in quantidade_corredor]
model.linear_constraints.add(
    lin_expr=[cplex.SparsePair(combined_indices, combined_values)],
    senses=["L"],
    rhs=[0],
    names=["capacity_constraint"]
)

# Restrições por item
for item in range(n_itens):
    pedido_coeffs = []
    pedido_vars = []
    for i in range(n_pedidos):
        if parsed_data['orders'][i][item] > 0:
            pedido_vars.append(pedido_X_indices[i])
            pedido_coeffs.append(parsed_data['orders'][i][item])
    
    corredor_coeffs = []
    corredor_vars = []
    for j in range(n_corredores):
        aisle_val = parsed_data['aisles'][j][item]
        if aisle_val > 0:
            corredor_vars.append(corredor_Y_indices[j])
            corredor_coeffs.append(aisle_val)
    
    if pedido_vars or corredor_vars:
        all_vars = pedido_vars + corredor_vars
        all_coeffs = pedido_coeffs + [-c for c in corredor_coeffs]
        model.linear_constraints.add(
            lin_expr=[cplex.SparsePair(all_vars, all_coeffs)],
            senses=["L"],
            rhs=[0],
            names=[f"item_constraint_{item}"]
        )

# Fixar variáveis inviáveis
for i in range(n_pedidos):
    if quantidade_pedidos[i] > UB:
        model.variables.set_upper_bounds(pedido_X_indices[i], 0)
        model.variables.set_lower_bounds(pedido_X_indices[i], 0)

# Restrições de aceleração
n_max_UB = parsed_data['n_max_pedidos_UB']
model.linear_constraints.add(
    lin_expr=[cplex.SparsePair(pedido_X_indices, [1]*n_pedidos)],
    senses=["L"],
    rhs=[n_max_UB - 1],
    names=["UB_acceleration"]
)

n_min_LB = parsed_data['n_min_pedidos_LB']
model.linear_constraints.add(
    lin_expr=[cplex.SparsePair(pedido_X_indices, [1]*n_pedidos)],
    senses=["G"],
    rhs=[n_min_LB],
    names=["LB_acceleration"]
)

# Loop principal de otimização
best = 0
best_A = 0
print("*****INICIO*****")
total_temp = time.time()

for a in range(n_corredores):
    # Adiciona restrições temporárias
    constr1 = model.linear_constraints.add(
        lin_expr=[cplex.SparsePair(corredor_Y_indices, [1]*n_corredores)],
        senses=["E"],
        rhs=[a + 1]
        #names=[f"temp_corredores_{a}"]
    )[0]

    constr2 = model.linear_constraints.add(
        lin_expr=[cplex.SparsePair(pedido_X_indices, quantidade_pedidos)],
        senses=["G"],
        rhs=[best * (a + 1)]
        #names=[f"temp_obj_{a}"]
    )[0]

    start_time = time.time()
    try:
        model.solve()
    except CplexError as exc:
        print(exc)
        continue

    status = model.solution.get_status()
    if status == model.solution.status.MIP_optimal:
        obj_val = model.solution.get_objective_value()
        current_best = obj_val / (a + 1)
        print(f'Obj: {current_best:.2f}, A = {a + 1}')
        print(f"Tempo = {time.time() - start_time:.4f}")
        
        if current_best >= best:
            best = current_best
            best_A = a + 1

            # Extrair solução
            melhor_pedidos = [i for i in range(n_pedidos) 
                            if model.solution.get_values(pedido_X_indices[i]) > 0.9]
            melhor_corredores = [j for j in range(n_corredores) 
                                if model.solution.get_values(corredor_Y_indices[j]) > 0.9]
    else:
        print(f"Não tem solução - A = {a + 1} | Tempo = {time.time() - start_time:.4f}")

    # Remove restrições temporárias
    model.linear_constraints.delete([constr1, constr2])

    # Critério de parada antecipada
    if best >= UB / (a + 2):
        print("Não existe solução melhor")
        break

total_temp = time.time() - total_temp
print(f"\nTempo total: {total_temp}s")
print("\nMELHOR SOLUÇÃO")
print(f"Valor: {best:.2f}")
print(f"Corredores utilizados: {best_A}")

# Escrever output
with open("output.txt", "w") as f:
    f.write(f"{len(melhor_pedidos)}\n")
    for p in melhor_pedidos:
        f.write(f"{p}\n")
    f.write(f"{len(melhor_corredores)}\n")
    for c in melhor_corredores:
        f.write(f"{c}\n")