# Make Basics Tutorial

## What is Make?

Make is a build automation tool that automatically builds executable programs and libraries from source code by reading files called Makefiles. It determines which pieces of a program need to be recompiled and issues commands to recompile them.

## Why Use Make?

- **Automation**: Automatically rebuild only what has changed
- **Efficiency**: Saves time by not recompiling unchanged files
- **Consistency**: Same build process every time
- **Documentation**: Makefile serves as build documentation

## Basic Structure

A Makefile consists of rules with this format:

```makefile
target: dependencies
	command
```

**Important**: The command line MUST start with a TAB character (not spaces)!

## Key Concepts

### 1. Targets
The file or action you want to create/perform

### 2. Dependencies (Prerequisites)
Files that must exist/be updated before the target can be built

### 3. Commands (Recipes)
Shell commands that create the target

### 4. Variables
Store values you use multiple times

### 5. Phony Targets
Targets that don't represent actual files (like `clean`, `all`, `test`)

## Basic Example

```makefile
# Variable definition
CC = gcc
CFLAGS = -Wall -g

# Rule to build program
program: main.c
	$(CC) $(CFLAGS) main.c -o program

# Phony target to clean up
.PHONY: clean
clean:
	rm -f program
```

## Common Commands

- `make` - Builds the first target in the Makefile
- `make <target>` - Builds a specific target
- `make clean` - Usually removes built files
- `make -n` - Shows what would be done without doing it (dry run)

## Try It Out!

1. Run `make` to build the example program
2. Run `./hello` to execute it
3. Run `make clean` to remove built files
4. Try modifying a source file and run `make` again

## Next Steps

Look at the Makefile in this directory to see practical examples of:
- Multiple source files
- Pattern rules
- Automatic variables
- Complex dependencies
