import { describe, expect, it } from 'vitest';

describe('titles', () => {
  it('says \'hello\'', () => {});
  it("says \"bye\" !", () => {});
  it(`uses a template`, () => {
    expect(1).toBe(1);
  });
  it(`interpolates ${'nothing'}`, () => {});

  it('has a regex', () => {
    const half = (4) / 2;
    const r = /[)}]+\)/g;
    expect('})').toMatch(r);
  });

  it('has an object in a template', () => {
    const s = `value: ${ { a: 1 }.a } and ${`nested ${'}'}`}`;
    expect(s).toBe('value: 1 and nested }');
  });

  it('same', () => {});
  it('same', () => {
    expect(1).toBe(2);
  });
});
