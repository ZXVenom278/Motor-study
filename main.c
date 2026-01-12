#include <stdio.h>
#include "math_utils.h"

int main() {
    printf("=== Make Tutorial: Multi-file Project ===\n\n");

    int x = 10, y = 5;

    printf("Adding %d + %d = %d\n", x, y, add(x, y));
    printf("Multiplying %d * %d = %d\n", x, y, multiply(x, y));

    printf("\nThis program was built from multiple source files!\n");
    return 0;
}
