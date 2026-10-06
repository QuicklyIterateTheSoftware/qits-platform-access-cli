import { describe, expect, it } from 'vitest';

describe('Ledger', () => {
  it('adds two entries', () => {
    expect(2 + 2).toBe(4);
  });

  describe('when empty', () => {
    it('refuses a withdrawal', () => {
      expect('accepted').toBe('refused');
    });
  });

  it('waits too long', async () => {
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }, 100);

  it.skip('is not written yet', () => {});
});

it('sits at the top level', () => {
  expect(1).toBe(1);
});
