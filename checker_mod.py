import numpy as np

class WaveOrderPicking:
    def __init__(self):
        self.orders = None
        self.aisles = None
        self.wave_size_lb = None
        self.wave_size_ub = None

    def read_input(self, input_file_path):
        with open(input_file_path, 'r') as file:
            lines = file.readlines()
            first_line = lines[0].strip().split()
            o, i, a = int(first_line[0]), int(first_line[1]), int(first_line[2])

            # Read orders
            self.orders = []
            for j in range(o):
                order_line = lines[j + 1].strip().split()
                d = int(order_line[0])
                order_map = {int(order_line[2 * k + 1]): int(order_line[2 * k + 2]) for k in range(d)}
                self.orders.append(order_map)

            # Read aisles
            self.aisles = []
            for j in range(a):
                aisle_line = lines[j + o + 1].strip().split()
                d = int(aisle_line[0])
                aisle_map = {int(aisle_line[2 * k + 1]): int(aisle_line[2 * k + 2]) for k in range(d)}
                self.aisles.append(aisle_map)

            # Read wave size bounds
            bounds = lines[o + a + 1].strip().split()
            self.wave_size_lb = int(bounds[0])
            self.wave_size_ub = int(bounds[1])

    def read_output(self, output_file_path):
        with open(output_file_path, 'r') as file:
            lines = file.readlines()
            num_orders = int(lines[0].strip())
            selected_orders = [int(lines[i + 1].strip()) for i in range(num_orders)]
            num_aisles = int(lines[num_orders + 1].strip())
            visited_aisles = [int(lines[num_orders + 2 + i].strip()) for i in range(num_aisles)]

        selected_orders = list(set(selected_orders))
        visited_aisles = list(set(visited_aisles))
        return selected_orders, visited_aisles

    def is_solution_feasible(self, selected_orders, visited_aisles):
        total_units_picked = 0
        for order in selected_orders:
            total_units_picked += np.sum(list(self.orders[order].values()))

        # Check if total units picked are within bounds
        if not (self.wave_size_lb <= total_units_picked <= self.wave_size_ub):
            return False

        # Compute all items that are required by the selected orders
        required_items = set()
        for order in selected_orders:
            required_items.update(self.orders[order].keys())

        # Check if all required items are available in the visited aisles
        for item in required_items:
            total_required = sum(self.orders[order].get(item, 0) for order in selected_orders)
            total_available = sum(self.aisles[aisle].get(item, 0) for aisle in visited_aisles)
            if total_required > total_available:
                return False

        return True

    def compute_objective_function(self, selected_orders, visited_aisles):
        total_units_picked = 0

        # Calculate total units picked
        for order in selected_orders:
            total_units_picked += np.sum(list(self.orders[order].values()))

        # Calculate the number of visited aisles
        num_visited_aisles = len(visited_aisles)

        # Objective function: total units picked / number of visited aisles
        return total_units_picked / num_visited_aisles

if __name__ == "__main__":
    import os
    import glob
    
    # Configure paths
    INPUT_DIR = "others"
    OUTPUT_DIR = "results"
    
    # Get all instance files
    input_files = sorted(glob.glob(os.path.join(INPUT_DIR, "instance*.txt")))
    
    if not input_files:
        print(f"No instance files found in {INPUT_DIR}/")
        exit(1)
    
    # Process each instance
    for input_file in input_files:
        # Extract instance number
        base_name = os.path.basename(input_file)
        instance_num = ''.join(filter(str.isdigit, base_name))
        output_file = os.path.join(OUTPUT_DIR, f"instance_{instance_num}.txt")
        
        print(f"\n{'='*50}")
        print(f"Processing Instance: {base_name}")
        
        if not os.path.exists(output_file):
            print(f"❌ Solution file not found: {os.path.basename(output_file)}")
            continue
        
        # Validate solution
        wave_order_picking = WaveOrderPicking()
        try:
            wave_order_picking.read_input(input_file)
            selected_orders, visited_aisles = wave_order_picking.read_output(output_file)
            
            is_feasible = wave_order_picking.is_solution_feasible(selected_orders, visited_aisles)
            objective_value = wave_order_picking.compute_objective_function(selected_orders, visited_aisles)
            
            print(f"Solution file: {os.path.basename(output_file)}")
            print(f"Selected orders: {len(selected_orders)}")
            print(f"Visited aisles: {len(visited_aisles)}")
            print(f"Feasible: {'✅ Yes' if is_feasible else '❌ No'}")
            
            if is_feasible:
                print(f"Objective Value: {objective_value:.4f}")
            else:
                # Detailed feasibility check
                total_units = sum(sum(order.values()) for idx, order in enumerate(wave_order_picking.orders) if idx in selected_orders)
                print(f"Total units: {total_units} (Required: {wave_order_picking.wave_size_lb}-{wave_order_picking.wave_size_ub})")
                
                # Check item coverage
                required_items = set()
                for order_idx in selected_orders:
                    required_items.update(wave_order_picking.orders[order_idx].keys())
                
                missing_items = []
                for item in required_items:
                    required_qty = sum(wave_order_picking.orders[o].get(item, 0) for o in selected_orders)
                    available_qty = sum(wave_order_picking.aisles[a].get(item, 0) for a in visited_aisles)
                    if required_qty > available_qty:
                        missing_items.append((item, required_qty, available_qty))
                
                if missing_items:
                    print("\nMissing Items:")
                    for item, req, avail in missing_items:
                        print(f"  Item {item}: Required {req}, Available {avail}")
                
        except Exception as e:
            print(f"🚨 Error processing files: {str(e)}")