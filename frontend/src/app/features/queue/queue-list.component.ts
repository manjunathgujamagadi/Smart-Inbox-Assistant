import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Category, MessageSummaryDto } from '../../core/models/message.model';
import { InboxStore } from '../../core/services/inbox-store.service';
import { MessageApiService } from '../../core/services/message-api.service';
import { CATEGORIES, CATEGORY_META, ReviewState, effectiveCategory, reviewState } from '../../core/ui-meta';
import { CategoryBadgeComponent } from '../../shared/category-badge.component';
import { ConfidenceMeterComponent } from '../../shared/confidence-meter.component';

type Mode = 'mail' | 'literature';
type SortKey = 'priority' | 'newest' | 'oldest' | 'confidence';
type ReviewFilter = 'ALL' | 'PENDING' | 'REVIEWED';

const PRIORITY: Record<Category, number> = { ICSR: 0, PQC: 1, MI: 2, NOT_RELEVANT: 3 };

interface Row {
  m: MessageSummaryDto;
  review: ReviewState;
  categories: Category[];
  topCategory: Category | null;
  minConfidence: number | null;
  relevantCases: number;
  processing: boolean;
}

@Component({
  selector: 'app-queue-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatMenuModule,
    MatProgressBarModule,
    MatTooltipModule,
    CategoryBadgeComponent,
    ConfidenceMeterComponent,
  ],
  templateUrl: './queue-list.component.html',
  styleUrl: './queue-list.component.scss',
})
export class QueueListComponent {
  readonly store = inject(InboxStore);
  private api = inject(MessageApiService);
  private snackBar = inject(MatSnackBar);

  readonly mode: Mode = inject(ActivatedRoute).snapshot.data['mode'] ?? 'mail';
  readonly categories = CATEGORIES;
  readonly categoryMeta = CATEGORY_META;

  readonly search = signal('');
  readonly reviewFilter = signal<ReviewFilter>('ALL');
  readonly categoryFilter = signal<Category | null>(null);
  readonly sort = signal<SortKey>('priority');

  running = false;
  uploading = false;
  checkingMail = false;
  retrying = false;
  dragOver = false;

  private readonly source = computed(() =>
    this.mode === 'literature' ? this.store.literatureMessages() : this.store.mailMessages(),
  );

  readonly rows = computed<Row[]>(() => this.source().map((m) => this.toRow(m)));

  readonly stats = computed(() => {
    const rows = this.rows();
    const count = (c: Category) => rows.filter((r) => r.categories.includes(c)).length;
    return {
      total: rows.length,
      pending: rows.filter((r) => r.review === 'PENDING').length,
      reviewed: rows.filter((r) => r.review === 'REVIEWED').length,
      processing: rows.filter((r) => r.processing).length,
      failed: rows.filter((r) => r.m.status === 'FAILED').length,
      byCategory: { ICSR: count('ICSR'), PQC: count('PQC'), MI: count('MI'), NOT_RELEVANT: count('NOT_RELEVANT') },
      cases: rows.reduce((n, r) => n + r.m.literatureCases.length, 0),
      relevantCases: rows.reduce((n, r) => n + r.relevantCases, 0),
      articlesWithCases: rows.filter((r) => r.relevantCases > 0).length,
    };
  });

  readonly filtered = computed<Row[]>(() => {
    const q = this.search().trim().toLowerCase();
    const rf = this.reviewFilter();
    const cf = this.categoryFilter();
    const list = this.rows().filter((r) => {
      if (rf !== 'ALL' && r.review !== rf) return false;
      if (cf && !r.categories.includes(cf)) return false;
      if (q) {
        const hay = `${r.m.subject ?? ''} ${r.m.sender ?? ''} ${r.categories.join(' ')}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
    return [...list].sort(this.comparator(this.sort()));
  });

  readonly hasFilters = computed(
    () => !!this.search() || this.reviewFilter() !== 'ALL' || this.categoryFilter() !== null,
  );

  readonly sortLabels: Record<SortKey, string> = {
    priority: 'Priority',
    newest: 'Newest first',
    oldest: 'Oldest first',
    confidence: 'Lowest confidence',
  };

  private toRow(m: MessageSummaryDto): Row {
    const active = m.classifications.filter((c) => c.reviewerStatus !== 'REJECTED');
    const categories = [...new Set(active.map(effectiveCategory))].sort((a, b) => PRIORITY[a] - PRIORITY[b]);
    const confidences = [...m.classifications.map((c) => c.confidence), ...m.literatureCases.map((c) => c.confidence)];
    return {
      m,
      review: reviewState(m),
      categories,
      topCategory: categories[0] ?? null,
      minConfidence: confidences.length ? Math.min(...confidences) : null,
      relevantCases: m.literatureCases.filter((c) => c.reviewerIsRelevant ?? c.relevantCase).length,
      processing: m.status !== 'PROCESSED' && m.status !== 'FAILED',
    };
  }

  private comparator(key: SortKey): (a: Row, b: Row) => number {
    const time = (r: Row) => (r.m.receivedAt ? Date.parse(r.m.receivedAt) : 0);
    switch (key) {
      case 'newest':
        return (a, b) => time(b) - time(a);
      case 'oldest':
        return (a, b) => time(a) - time(b);
      case 'confidence':
        return (a, b) => (a.minConfidence ?? 2) - (b.minConfidence ?? 2);
      default:
        // Pending before reviewed, then by category severity, then newest first.
        return (a, b) => {
          const pa = a.review === 'PENDING' ? 0 : 1;
          const pb = b.review === 'PENDING' ? 0 : 1;
          if (pa !== pb) return pa - pb;
          const ca = a.topCategory ? PRIORITY[a.topCategory] : a.relevantCases ? 0 : 4;
          const cb = b.topCategory ? PRIORITY[b.topCategory] : b.relevantCases ? 0 : 4;
          if (ca !== cb) return ca - cb;
          return time(b) - time(a);
        };
    }
  }

  toggleCategory(c: Category): void {
    this.categoryFilter.set(this.categoryFilter() === c ? null : c);
  }

  showReview(f: ReviewFilter): void {
    this.categoryFilter.set(null);
    this.reviewFilter.set(f);
  }

  clearFilters(): void {
    this.search.set('');
    this.reviewFilter.set('ALL');
    this.categoryFilter.set(null);
  }

  trackById(_: number, row: Row): number {
    return row.m.id;
  }

  timeAgo(iso: string | null): string {
    if (!iso) return '—';
    const diff = (Date.now() - Date.parse(iso)) / 1000;
    if (diff < 60) return 'just now';
    if (diff < 3600) return `${Math.floor(diff / 60)} min ago`;
    if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`;
    if (diff < 86400 * 7) return `${Math.floor(diff / 86400)} d ago`;
    return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
  }

  initials(sender: string | null): string {
    if (!sender) return '?';
    const name = sender.split('@')[0].replace(/[._-]+/g, ' ').trim();
    const parts = name.split(' ').filter(Boolean);
    return ((parts[0]?.[0] ?? '?') + (parts[1]?.[0] ?? '')).toUpperCase();
  }

  // ---------- actions ----------

  private notify(message: string, ok = true): void {
    this.snackBar.open(message, 'Dismiss', { duration: ok ? 4000 : 7000, panelClass: ok ? 'snack-ok' : 'snack-err' });
  }

  private errText(err: any): string {
    return err?.error?.error ?? err?.message ?? 'unknown error';
  }

  checkMailbox(): void {
    this.checkingMail = true;
    this.api.fetchMail().subscribe({
      next: (res) => {
        this.checkingMail = false;
        const msg = res.ingested ? `${res.ingested} new email(s) received — processing in background` : 'No new emails';
        this.notify(res.failed ? `${msg} (${res.failed} failed, see logs)` : msg, !res.failed);
        this.store.refresh(true);
      },
      error: (err) => {
        this.checkingMail = false;
        this.notify('Mailbox check failed: ' + this.errText(err), false);
        this.store.refresh(true);
      },
    });
  }

  runBatch(): void {
    this.running = true;
    this.api.runBatch().subscribe({
      next: (res) => {
        this.running = false;
        this.notify(`Ingested ${res.ingested} document(s) — processing in background`);
        setTimeout(() => this.store.refresh(), 1500);
      },
      error: (err) => {
        this.running = false;
        this.notify('Batch run failed: ' + this.errText(err), false);
      },
    });
  }

  retryFailed(): void {
    this.retrying = true;
    this.api.retryFailed().subscribe({
      next: (res) => {
        this.retrying = false;
        this.notify(`Re-queued ${res.retried} failed document(s)`);
        this.store.refresh(true);
      },
      error: (err) => {
        this.retrying = false;
        this.notify('Retry failed: ' + this.errText(err), false);
      },
    });
  }

  onFilesSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = input.files ? Array.from(input.files) : [];
    input.value = ''; // allow re-selecting the same file(s) later
    this.upload(files);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver = false;
    const files = Array.from(event.dataTransfer?.files ?? []);
    const pdfs = files.filter((f) => f.type === 'application/pdf' || f.name.toLowerCase().endsWith('.pdf'));
    if (pdfs.length < files.length) this.notify(`Skipped ${files.length - pdfs.length} non-PDF file(s)`, false);
    this.upload(pdfs);
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragOver = true;
  }

  private upload(files: File[]): void {
    if (!files.length) return;
    this.uploading = true;
    this.api.uploadLiterature(files).subscribe({
      next: (res) => {
        this.uploading = false;
        this.notify(`Uploaded ${res.uploaded} article(s) — screening in background`);
        setTimeout(() => this.store.refresh(), 1500);
      },
      error: (err) => {
        this.uploading = false;
        this.notify('Upload failed: ' + this.errText(err), false);
      },
    });
  }
}
