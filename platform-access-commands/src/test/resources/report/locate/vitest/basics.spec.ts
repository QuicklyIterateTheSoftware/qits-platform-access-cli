import { describe, expect, it, test } from 'vitest';

it('runs at the top level', () => {
  expect(1).toBe(1);
});

test('also at the top level', function () {
  expect(2).toBe(2);
});

describe('Ledger', () => {
  describe('when empty', () => {
    it('refuses a withdrawal', () => {
      expect('accepted').toBe('refused');
    });
  });

  describe("when full", function () {
    it.only('accepts a deposit', () => {});
    it.skip('is skipped', () => {});
    test.concurrent('runs concurrently', async () => {
      await Promise.resolve();
    });
    it.fails('is expected to fail', () => {
      throw new Error(')');
    });
  });

  it('refuses a withdrawal', () => {
    expect(true).toBe(false);
  });
});
