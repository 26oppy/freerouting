/* Freerouting BGA Escape Kernels — OpenCL 1.2 C
 *
 * Full kernel implementations are codified in Milestone 3.
 * Stubs are registered here to validate compilation and runtime linking in Milestone 1.
 */

__kernel void rasterize_obstacles_and_rudy(
    __global int* obstacleGrid,
    int width,
    int height,
    int layerCount) {
  // Stub for M1 compilation
}

__kernel void evaluate_via_dogbone_sites(
    __global int* pinBuffer,
    __global int* siteBuffer,
    int pinCount) {
  // Stub for M1 compilation
}

__kernel void expand_ring_candidates(
    __global int* candidateBuffer,
    __global int* obstacleGrid,
    int ringIdx) {
  // Stub for M1 compilation
}

__kernel void resolve_and_commit_paths(
    __global int* candidateBuffer,
    __global uint* cellWinner,
    __global uint* cellDemand,
    int pinCount) {
  // Stub for M1 compilation
}
