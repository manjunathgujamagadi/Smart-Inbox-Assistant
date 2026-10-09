import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
import { confidenceBand } from '../core/ui-meta';

/** Small horizontal bar + percentage, coloured by confidence band (high / medium / low). */
@Component({
  selector: 'app-confidence-meter',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  template: `
    <span class="meter" [ngClass]="band" [matTooltip]="tooltip" *ngIf="value != null; else none">
      <span class="track"><span class="fill" [style.width.%]="value * 100"></span></span>
      <span class="pct">{{ value * 100 | number: '1.0-0' }}%</span>
    </span>
    <ng-template #none><span class="pct none">n/a</span></ng-template>
  `,
  styles: `
    :host { display: inline-flex; }
    .meter { display: inline-flex; align-items: center; gap: 6px; }
    .track { width: var(--meter-width, 48px); height: 6px; border-radius: 3px; background: #e7ebee; overflow: hidden; }
    .fill { display: block; height: 100%; border-radius: 3px; }
    .pct { font-size: 12px; font-weight: 600; font-variant-numeric: tabular-nums; min-width: 32px; }
    .high .fill { background: #2e7d32; } .high .pct { color: #2e7d32; }
    .medium .fill { background: #d97706; } .medium .pct { color: #b45309; }
    .low .fill { background: #c62828; } .low .pct { color: #c62828; }
    .none { color: var(--text-3); font-weight: 500; }
  `,
})
export class ConfidenceMeterComponent {
  @Input() value: number | null = null;

  get band() {
    return confidenceBand(this.value);
  }

  get tooltip(): string {
    switch (this.band) {
      case 'high':
        return 'High confidence';
      case 'medium':
        return 'Medium confidence - worth a quick check';
      case 'low':
        return 'Low confidence - please verify against the source';
      default:
        return '';
    }
  }
}
