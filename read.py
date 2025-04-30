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

    return {
        'num_orders': o,
        'num_items': i,
        'num_aisles': a,
        'orders': orders_matrix,
        'aisles': aisles_matrix,
        'LB': LB,
        'UB': UB
    }


def best_n_corredores(parsed_data,n:int):
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


"""
# MUITO UTEIS PARA IDENTIFICAR PROBLEMAS INFACTIVEIS
# Também podemos utilizar para auxiliar na redução de combinações em problemas combinatorios

    A =      1 , 2, 3 ,4 ,5
cenoura     [max A = 1, max A =2  .... max A = n_corredores]
banana      [max A = 1, max A =2  .... max A = n_corredores]
abacaxi     [max A = 1, max A =2  .... max A = n_corredores]
maça        [max A = 1, max A =2  .... max A = n_corredores]
laranja     [max A = 1, max A =2  .... max A = n_corredores]
"""

# Ainda não está em uso, por isso não adicionei ao programa principal
def max_suply_n_corredores(parsed_data):
    lista_max_itens = []
    # o maximo de cada coluna da matriz aisless
    # [1°max, 1°max + 2°max, 1°max + 2°max + 3°max...]
    # firs order max for item
    n_itens = parsed_data['num_items']
    arr = np.array(parsed_data['aisles'])
    itens_list = []
    # adicionar as colunas de aisles em itens_list
    # 1 - pegar coluna
    # 2 - ordenar coluna
    # 3 - adicionar coluna em itens_list
    for i in range(n_itens):
        coluna = arr[:, i]
        coluna_ordenada = np.sort(coluna)
        itens_list.append(coluna_ordenada)
    # pegar o maximo de cada coluna
    # [1°max, 1°max + 2°max, 1°max + 2°max + 3°max...]
    for item in itens_list:
        max_itens = []
        for i in range(len(item)):
            max_itens.append(item[0:i+1].sum())
        lista_max_itens.append(max_itens)
    return lista_max_itens

def provar_factibilidade(parsed_data, pedidos_selecionados:list, corredores_selecionados:list):
    pedidos = []
    for i in pedidos_selecionados:
        pedidos.append(parsed_data['orders'][i])
    corredores = []
    for i in corredores_selecionados:
        corredores.append(parsed_data['aisles'][i])
    pedidos = np.array(pedidos)
    corredores = np.array(corredores)

    # soma das colunas de pedidos menos soma das colunas de corredores

    res_itens = []
    for i in range(parsed_data['num_items']):
        res_itens.append(corredores[:, i].sum() - pedidos[:, i].sum())
    
    for i in res_itens:
        if i < 0:
            return False
    return True


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