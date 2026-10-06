export function tick(seconds: number): number {
  if (seconds < 0) {
    return 0;
  }
  return seconds + 1;
}
