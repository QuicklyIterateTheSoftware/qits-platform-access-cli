export interface Entry {
  readonly amount: number;
  readonly memo: string;
}

export class Ledger {
  private readonly entries: Entry[] = [];

  add(amount: number, memo: string): void {
    if (amount === 0) {
      throw new Error('an entry of nothing');
    }
    this.entries.push({ amount, memo });
  }

  balance(): number {
    return this.entries.reduce((sum, e) => sum + e.amount, 0);
  }

  largest(): Entry | undefined {
    let best: Entry | undefined;
    for (const e of this.entries) {
      if (!best || e.amount > best.amount) {
        best = e;
      }
    }
    return best;
  }
}

export function format(amount: number): string {
  return amount.toFixed(2);
}
