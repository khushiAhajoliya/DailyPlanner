import { existsSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { randomUUID } from "node:crypto";

export interface Entity { id: string; createdAt: string; updatedAt: string }

/** Small JSON-file collection with atomic writes. Good enough for a single-instance backend. */
export class JsonCollection<T extends Entity> {
  private items: T[];
  constructor(private readonly file: string) {
    this.items = existsSync(file) ? (JSON.parse(readFileSync(file, "utf8")) as T[]) : [];
  }
  private flush() {
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.items, null, 2));
    renameSync(tmp, this.file);
  }
  all(): T[] {
    return [...this.items].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  }
  get(id: string): T | undefined {
    return this.items.find((i) => i.id === id);
  }
  create(data: Omit<T, keyof Entity>): T {
    const now = new Date().toISOString();
    const item = { ...data, id: randomUUID(), createdAt: now, updatedAt: now } as T;
    this.items.push(item);
    this.flush();
    return item;
  }
  /** Create or replace an item with a client-chosen id (offline-first clients). */
  upsert(id: string, data: Omit<T, keyof Entity>): T {
    const now = new Date().toISOString();
    const i = this.items.findIndex((x) => x.id === id);
    const item = { ...data, id, createdAt: i >= 0 ? this.items[i].createdAt : now, updatedAt: now } as T;
    if (i >= 0) this.items[i] = item; else this.items.push(item);
    this.flush();
    return item;
  }
  update(id: string, data: Partial<Omit<T, keyof Entity>>): T | undefined {
    const i = this.items.findIndex((x) => x.id === id);
    if (i < 0) return undefined;
    this.items[i] = { ...this.items[i], ...data, updatedAt: new Date().toISOString() };
    this.flush();
    return this.items[i];
  }
  remove(id: string): boolean {
    const before = this.items.length;
    this.items = this.items.filter((x) => x.id !== id);
    if (this.items.length !== before) this.flush();
    return this.items.length !== before;
  }
}
