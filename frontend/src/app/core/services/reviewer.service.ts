import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'smart-inbox.reviewer';

/**
 * Holds the reviewer's display name so every review submission is attributed to a person in the
 * audit trail. Stored in the browser only - this prototype has no authentication.
 */
@Injectable({ providedIn: 'root' })
export class ReviewerService {
  readonly name = signal<string>(this.read());

  setName(value: string): void {
    const name = value.trim() || 'reviewer';
    this.name.set(name);
    try {
      localStorage.setItem(STORAGE_KEY, name);
    } catch {
      /* storage unavailable - keep in memory only */
    }
  }

  private read(): string {
    try {
      return localStorage.getItem(STORAGE_KEY) || 'reviewer';
    } catch {
      return 'reviewer';
    }
  }
}
