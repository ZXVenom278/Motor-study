# ============================================
# MAKE BASICS TUTORIAL - Annotated Makefile
# ============================================

# VARIABLES
# ---------
# Variables store values you use repeatedly
# Use $(VARIABLE_NAME) to reference them

CC = gcc                    # Compiler to use
CFLAGS = -Wall -g          # Compiler flags: -Wall (all warnings), -g (debug info)
TARGET = program           # Name of final executable
HELLO_TARGET = hello       # Name of simple hello program

# Object files (compiled but not linked)
OBJS = main.o math_utils.o

# ============================================
# PHONY TARGETS
# ============================================
# .PHONY declares targets that don't create files
# This prevents conflicts if files named "all" or "clean" exist

.PHONY: all clean help test

# ============================================
# DEFAULT TARGET
# ============================================
# The first target is the default (runs when you type just "make")

all: $(TARGET) $(HELLO_TARGET)
	@echo "✓ Build complete! Try running: ./$(TARGET) or ./$(HELLO_TARGET)"

# ============================================
# SIMPLE EXAMPLE
# ============================================
# Single-file program (good for beginners)

$(HELLO_TARGET): hello.c
	@echo "Building simple hello program..."
	$(CC) $(CFLAGS) hello.c -o $(HELLO_TARGET)

# ============================================
# MULTI-FILE PROJECT
# ============================================

# Final executable depends on all object files
$(TARGET): $(OBJS)
	@echo "Linking $(TARGET)..."
	$(CC) $(CFLAGS) $(OBJS) -o $(TARGET)

# Rule to compile main.c into main.o
# This will only recompile if main.c or math_utils.h changes
main.o: main.c math_utils.h
	@echo "Compiling main.c..."
	$(CC) $(CFLAGS) -c main.c

# Rule to compile math_utils.c into math_utils.o
math_utils.o: math_utils.c math_utils.h
	@echo "Compiling math_utils.c..."
	$(CC) $(CFLAGS) -c math_utils.c

# ============================================
# PATTERN RULE (Advanced)
# ============================================
# This is a more elegant way to compile .c files to .o files
# The % is a wildcard that matches any filename
# $< means "first dependency", $@ means "target name"
#
# Uncomment these and comment out the explicit rules above to try it:
#
# %.o: %.c
# 	@echo "Compiling $<..."
# 	$(CC) $(CFLAGS) -c $<

# ============================================
# UTILITY TARGETS
# ============================================

# Clean up built files
clean:
	@echo "Cleaning up..."
	rm -f $(TARGET) $(HELLO_TARGET) $(OBJS)
	@echo "✓ Clean complete"

# Run the program
test: $(TARGET)
	@echo "Running $(TARGET)..."
	./$(TARGET)

# Show help information
help:
	@echo "Available targets:"
	@echo "  make          - Build everything (default)"
	@echo "  make all      - Build everything"
	@echo "  make hello    - Build simple hello program"
	@echo "  make program  - Build multi-file program"
	@echo "  make clean    - Remove all built files"
	@echo "  make test     - Build and run the program"
	@echo "  make help     - Show this help message"
	@echo ""
	@echo "Try these commands:"
	@echo "  1. make           (builds both programs)"
	@echo "  2. ./hello        (run simple program)"
	@echo "  3. ./program      (run multi-file program)"
	@echo "  4. make clean     (clean up)"
	@echo "  5. touch main.c && make  (rebuild only what changed)"
