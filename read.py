#ler arquivo

def parse_input(file_path):
    with open(file_path, 'r') as f:
        lines = [line.strip() for line in f.readlines() if line.strip()]

    # Parse primeira linha (o, i, a)
    o, i, a = map(int, lines[0].split())
    current_line = 1
    
    # Parse pedidos
    orders = []
    for _ in range(o):
        parts = list(map(int, lines[current_line].split()))
        k = parts[0]
        items = parts[1:]
        order = []
        for idx in range(0, len(items), 2):
            item_num = items[idx]
            quantity = items[idx + 1]
            order.append((item_num, quantity))
        orders.append(order)
        current_line += 1

    # Parse corredores
    aisles = []
    for _ in range(a):
        parts = list(map(int, lines[current_line].split()))
        l = parts[0]
        items = parts[1:]
        aisle = []
        for idx in range(0, len(items), 2):
            item_num = items[idx]
            quantity = items[idx + 1]
            aisle.append((item_num, quantity))
        aisles.append(aisle)
        current_line += 1

    # Parse limites da wave
    LB, UB = map(int, lines[current_line].split())

    return {
        'num_orders': o,
        'num_items': i,
        'num_aisles': a,
        'orders': orders,
        'aisles': aisles,
        'LB': LB,
        'UB': UB
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
