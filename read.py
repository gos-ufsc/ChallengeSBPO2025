#ler arquivo
import numpy as np

def parse_input(file_path):
    with open(file_path, 'r') as f:
        lines = [line.strip() for line in f.readlines() if line.strip()]

    # Parse primeira linha (o, i, a)
    o, i, a = map(int, lines[0].split())
    current_line = 1
    
    # Matriz de pedidos (o x i)
    orders_matrix = [[0] * i for _ in range(o)]
    for order_idx in range(o):
        parts = list(map(int, lines[current_line].split()))
        k = parts[0]
        items = parts[1:]
        for idx in range(0, 2*k, 2):  # Processa pares (item, quantidade)
            item = items[idx]
            qty = items[idx + 1]
            orders_matrix[order_idx][item] = qty
        current_line += 1

    # Matriz de corredores (a x i)
    aisles_matrix = [[0] * i for _ in range(a)]
    for aisle_idx in range(a):
        parts = list(map(int, lines[current_line].split()))
        l = parts[0]
        items = parts[1:]
        for idx in range(0, 2*l, 2):  # Processa pares (item, quantidade)
            item = items[idx]
            qty = items[idx + 1]
            aisles_matrix[aisle_idx][item] = qty
        current_line += 1

    # Parse limites da wave
    LB, UB = map(int, lines[current_line].split())

    # dados para simplificação
    soma_pedidos = []
    soma_corredor = []
    for pedidos in orders_matrix:
        soma_pedidos.append(sum(pedidos))
    for corredor in aisles_matrix:
        soma_corredor.append(sum(corredor))

    return {
        'num_orders': o,
        'num_items': i,
        'num_aisles': a,
        'orders': orders_matrix,
        'aisles': aisles_matrix,
        'LB': LB,
        'UB': UB,
        'soma_pedidos': soma_pedidos,
        'soma_corredor': soma_corredor
    }

# Exemplo de uso
if __name__ == "__main__":
    example = "datasets/a/instance_0020.txt"
    parsed_data = parse_input(example)
    print("Número de pedidos:", parsed_data['num_orders'])
    #print("Primeiro pedido:", parsed_data['orders'][0])
    print("Todos pedidos")
    for i in parsed_data['orders']:
        print(i)
    print("Primeiro pedido:", parsed_data['orders'][0])
    print("Número de corredores:", parsed_data['num_aisles'])
    #print("Primeiro corredor:", parsed_data['aisles'][0])
    print("Todos corredores")
    for i in parsed_data['aisles']:
        print(i)
    print("Limites da wave:", "LB:", parsed_data['LB'], "UB:", parsed_data['UB'])
    print("******SIMPLIFICACAO******")
    print("soma_pedidos:", parsed_data['soma_pedidos'])
    print("soma_corredor:", parsed_data['soma_corredor'])

    print("\nLimites da wave:", parsed_data['LB'], parsed_data['UB'])

def best_n_corredores(parsed_data,n):
    melhores = []
    melhores_indices = []
    melhores_soma = []
    arr = np.array(parsed_data['soma_corredor'])
    indices_ordenados = np.argsort(arr)
    for i in indices_ordenados[-n:]:
        melhores.append(parsed_data['aisles'][i])
        melhores_indices.append(i)
        melhores_soma.append(parsed_data['soma_corredor'][i])
    return melhores,melhores_indices,melhores_soma

