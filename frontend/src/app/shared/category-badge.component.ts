import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Category, ReviewerStatus } from '../core/models/message.model';
import { CATEGORY_META } from '../core/ui-meta';

/** Colour-coded category label, optionally with the AI confidence and the reviewer's verdict. */
@Component({
  selector: 'app-category-badge',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatTooltipModule],
  template: `
    <span
      class="badge"
      [ngClass]="[meta.cssClass, compact ? 'compact' : '']"
      [class.rejected]="status === 'REJECTED'"
      [matTooltip]="tooltip"
    >
      <mat-icon>{{ meta.icon }}</mat-icon>
      <span class="label">{{ compact ? meta.short : meta.label }}</span>
      <span class="conf" *ngIf="confidence != null">{{ confidence * 100 | number: '1.0-0' }}%</span>
      <mat-icon class="status" *ngIf="status === 'ACCEPTED'">check_circle</mat-icon>
      <mat-icon class="status" *ngIf="status === 'OVERRIDDEN'">edit</mat-icon>
      <mat-icon class="status" *ngIf="status === 'REJECTED'">cancel</mat-icon>
    </span>
  `,
  styles: `
    :host { display: inline-flex; }
    .badge {
      display: inline-flex; align-items: center; gap: 4px;
      padding: 2px 8px 2px 6px; border-radius: 6px;
      font-size: 12px; font-weight: 600; line-height: 20px; white-space: nowrap;
      border: 1px solid transparent;
    }
    mat-icon { font-size: 15px; width: 15px; height: 15px; }
    .conf { font-weight: 500; opacity: 0.8; font-variant-numeric: tabular-nums; }
    .status { margin-left: 2px; }
    .icsr { background: var(--icsr-soft); color: var(--icsr); border-color: #f6c9c5; }
    .pqc { background: var(--pqc-soft); color: var(--pqc); border-color: #f5d9b0; }
    .mi { background: var(--mi-soft); color: var(--mi); border-color: #c9d7fb; }
    .nr { background: var(--nr-soft); color: var(--nr); border-color: #dbe0e4; }
    .rejected .label { text-decoration: line-through; }
    .rejected { opacity: 0.6; }
  `,
})
export class CategoryBadgeComponent {
  @Input({ required: true }) category!: Category;
  @Input() confidence: number | null = null;
  @Input() status: ReviewerStatus | null = null;
  @Input() compact = false;

  get meta() {
    return CATEGORY_META[this.category] ?? CATEGORY_META.NOT_RELEVANT;
  }

  get tooltip(): string {
    const parts = [`${this.meta.label}: ${this.meta.hint}`];
    if (this.status && this.status !== 'PENDING') parts.push(`Reviewer: ${this.status.toLowerCase()}`);
    return parts.join('\n');
  }
}
