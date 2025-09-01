import os
import subprocess
import sys
import platform

# Paths to the libraries
CPLEX_PATH="/opt/ibm/ILOG/CPLEX_Studio2211/cplex/bin/x86-64_linux/"
OR_TOOLS_PATH = "$HOME/Documents/or-tools/build/lib/"

USE_CPLEX = True
USE_OR_TOOLS = False

MAX_RUNNING_TIME = "605s"

def compile_code(source_folder):
    print(f"Compiling code in {source_folder}...")
    os.chdir(source_folder)
    result = subprocess.run(["mvn", "clean", "package"], capture_output=True, text=True)
    if result.returncode != 0:
        print("Maven compilation failed:")
        print(result.stderr)
        return False
    print("Maven compilation successful.")
    return True

# MODIFIED: The function now accepts a single 'input_file' path instead of an 'input_folder'.
def run_benchmark(source_folder, input_file, output_folder):
    os.chdir(source_folder)

    if not os.path.exists(output_folder):
        os.makedirs(output_folder)

    if USE_CPLEX and USE_OR_TOOLS:
        libraries = f"{OR_TOOLS_PATH}:{CPLEX_PATH}"
    elif USE_CPLEX:
        libraries = CPLEX_PATH
    elif USE_OR_TOOLS:
        libraries = OR_TOOLS_PATH

    if platform.system() == "Darwin":
        timeout_command = "gtimeout"
    else:
        timeout_command = "timeout"

    # MODIFIED: The 'for' loop is removed. The script now processes only the single file provided.
    if input_file.endswith(".txt"):
        # Get just the filename from the full path to create a corresponding output file
        filename = os.path.basename(input_file)
        print(f"Running {filename}")
        
        output_file_path = os.path.join(output_folder, f"{os.path.splitext(filename)[0]}.txt")
        
        with open(output_file_path, "w") as out:
            cmd = [timeout_command, MAX_RUNNING_TIME, "java", "-Xmx16g", "-jar", "target/ChallengeSBPO2025-1.0.jar",
                   input_file, # MODIFIED: Use the direct input_file path
                   output_file_path] # MODIFIED: Use the newly constructed output path
            if USE_CPLEX or USE_OR_TOOLS:
                cmd.insert(3, f"-Djava.library.path={libraries}")

            result = subprocess.run(cmd, stderr=subprocess.PIPE, text=True)
            if result.returncode != 0:
                print(f"Execution failed for {input_file}:")
                print(result.stderr)
    else:
        print(f"Error: The provided input is not a .txt file: {input_file}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        # MODIFIED: Updated the usage instructions.
        print("Usage: python run_challenge.py <source_folder> <input_file> <output_folder>")
        sys.exit(1)

    source_folder = sys.argv[1]
    # MODIFIED: The second argument is now a specific file.
    input_file = sys.argv[2]
    output_folder = sys.argv[3]

    if compile_code(source_folder):
        # MODIFIED: Pass the single input_file path to the function.
        run_benchmark(source_folder, input_file, output_folder)