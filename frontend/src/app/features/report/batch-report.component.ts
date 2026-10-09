import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { BatchReportDto } from '../../core/models/message.model';
import { MessageApiService } from '../../core/services/message-api.service';
import { formatDuration } from '../../core/ui-meta';

type SortKey = 'slowest' | 'id';

/** Section 3E "Handle a real batch": per-document processing time for the whole run. */
@Component({
  selector: 'app-batch-report',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './batch-report.component.html',
  styleUrl: './batch-report.component.scss',
})
export class BatchReportComponent implements OnInit, OnDestroy {
  private api = inject(MessageApiService);
  private snackBar = inject(MatSnackBar);
  private timer?: ReturnType<typeof setInterval>;

  readonly report = signal<BatchReportDto | null>(null);
  readonly loading = signal(true);
  readonly sort = signal<SortKey>('id');
  retrying = false;

  readonly fmt = formatDuration;
  readonly round = Math.round;

  readonly jobs = computed(() => {
    const jobs = [...(this.report()?.jobs ?? [])];
    return this.sort() === 'slowest'
      ? jobs.sort((a, b) => (b.durationMs ?? -1) - (a.durationMs ?? -1))
      : jobs.sort((a, b) => a.messageId - b.messageId);
  });

  readonly maxMs = computed(() => Math.max(1, ...(this.report()?.jobs ?? []).map((j) => j.durationMs ?? 0)));

  readonly totalMs = computed(() => (this.report()?.jobs ?? []).reduce((n, j) => n + (j.durationMs ?? 0), 0));

  readonly inFlight = computed(
    () => (this.report()?.jobs ?? []).filter((j) => j.status === 'PENDING' || j.status === 'PROCESSING').length,
  );

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(true), 5000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  load(silent = false): void {
    if (!silent) this.loading.set(true);
    this.api.getBatchReport().subscribe({
      next: (r) => {
        this.report.set(r);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        if (!silent) this.snackBar.open('Failed to load processing report', 'Dismiss', { duration: 4000, panelClass: 'snack-err' });
      },
    });
  }

  retryFailed(): void {
    this.retrying = true;
    this.api.retryFailed().subscribe({
      next: (res) => {
        this.retrying = false;
        this.snackBar.open(`Re-queued ${res.retried} failed document(s)`, 'Dismiss', { duration: 4000, panelClass: 'snack-ok' });
        this.load(true);
      },
      error: () => {
        this.retrying = false;
        this.snackBar.open('Retry failed', 'Dismiss', { duration: 5000, panelClass: 'snack-err' });
      },
    });
  }

  exportJson(): void {
    const r = this.report();
    if (!r) return;
    const blob = new Blob([JSON.stringify(r, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `batch-report-${new Date().toISOString().slice(0, 19).replace(/:/g, '')}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  barWidth(ms: number | null): number {
    return ms == null ? 0 : Math.max(1, (ms / this.maxMs()) * 100);
  }
}
