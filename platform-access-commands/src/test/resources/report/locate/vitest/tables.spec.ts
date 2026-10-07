import { describe, expect, it, test } from 'vitest';

describe('arithmetic', () => {
  it.each([
    [1, 1, 2],
    [2, 1, 3],
  ])('adds %i + %i', (a, b, expected) => {
    expect(a + b).toBe(expected);
  });

  test.each`
    a    | b    | expected
    ${1} | ${1} | ${2}
  `('returns $expected when $a is added to $b', ({ a, b, expected }) => {
    expect(a + b).toBe(expected);
  });
});

describe.each([
  { name: 'first' },
  { name: 'second' },
])('table $name', ({ name }) => {
  it('knows its name', () => {
    expect(name).toBeTruthy();
  });
});
