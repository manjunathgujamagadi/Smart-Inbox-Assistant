import { CommonModule } from '@angular/common';
import { Component, HostListener, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import {
  AttachmentDto,
  AuditEntryDto,
  Category,
  ClassificationDto,
  ExtractedFieldDto,
  ImageDto,
  LiteratureCaseDto,
  MessageDetailDto,
  ReviewRequest,
  TableDto,
} from '../../core/models/message.model';
import { InboxStore } from '../../core/services/inbox-store.service';
import { MessageApiService } from '../../core/services/message-api.service';
import { ReviewerService } from '../../core/services/reviewer.service';
import {
  CATEGORIES,
  CATEGORY_META,
  PDF_FLAVOR_META,
  confidenceBand,
  groupMeta,
  humanize,
  isNotStated,
  reviewState,
} from '../../core/ui-meta';
import { CategoryBadgeComponent } from '../../shared/category-badge.component';
import { ConfidenceMeterComponent } from '../../shared/confidence-meter.component';

type Decision = 'ACCEPTED' | 'OVERRIDDEN' | 'REJECTED';

interface UiClassification extends ClassificationDto {
  uiDecision: Decision;
  uiOverrideCategory: Category;
}

interface UiField extends ExtractedFieldDto {
  uiDecision: 'ACCEPTED' | 'OVERRIDDEN';
  uiOverrideValue: string;
  editing: boolean;
}

interface UiLiteratureCase extends LiteratureCaseDto {
  uiDecision: Decision;
  uiOverrideIsRelevant: boolean;
}

interface FieldGroupView {
  key: string;
  label: string;
  icon: string;
  fields: UiField[];
  stated: number;
}

type FieldFilter = 'ALL' | 'ATTENTION' | 'STATED';

/** Text segment of the email body, flagged when it matches the currently highlighted source value. */
interface Segment {
  text: string;
  hit: boolean;
}

const PROCESSING_POLL_MS = 4000;

@Component({
  selector: 'app-message-detail',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatSelectModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatTabsModule,
    MatProgressBarModule,
    MatTooltipModule,
    CategoryBadgeComponent,
    ConfidenceMeterComponent,
  ],
  templateUrl: './message-detail.component.html',
  styleUrl: './message-detail.component.scss',
})
export class MessageDetailComponent implements OnInit, OnDestroy {
  private api = inject(MessageApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private snackBar = inject(MatSnackBar);
  private sanitizer = inject(DomSanitizer);
  private store = inject(InboxStore);
  readonly reviewer = inject(ReviewerService);

  private safeUrlCache = new Map<string, SafeResourceUrl>();
  private pollTimer?: ReturnType<typeof setTimeout>;
  private sub?: Subscription;

  readonly categories = CATEGORIES;
  readonly categoryMeta = CATEGORY_META;
  readonly flavorMeta = PDF_FLAVOR_META;
  readonly humanize = humanize;
  readonly isNotStated = isNotStated;
  readonly band = confidenceBand;

  detail: MessageDetailDto | null = null;
  classifications: UiClassification[] = [];
  fields: UiField[] = [];
  literatureCases: UiLiteratureCase[] = [];
  groups: FieldGroupView[] = [];

  loading = true;
  submitting = false;
  selectedTab = 0;
  /** Page to open per attachment in the embedded PDF viewer (set when jumping to a fact's source). */
  pdfPage: Record<number, number> = {};
  highlight: string | null = null;
  highlightedFieldId: number | null = null;
  fieldFilter: FieldFilter = 'ALL';
  auditFilter: 'ALL' | 'AI' | 'REVIEWER' = 'ALL';
  auditOpen = false;
  expandedAudit = new Set<number>();
  expandedSummary = new Set<number>();
  showTranslation = new Set<number>();

  private readonly currentId = signal<number | null>(null);
  private readonly currentIsUpload = signal(false);

  /** Prev/next within messages of the same kind (mail vs upload), in queue order. */
  readonly position = computed(() => {
    const upload = this.currentIsUpload();
    const id = this.currentId();
    const list = this.store.messages().filter((m) => (m.sourceType === 'UPLOAD') === upload);
    const index = list.findIndex((m) => m.id === id);
    const after = [...list.slice(index + 1), ...list.slice(0, Math.max(index, 0))];
    return {
      index,
      total: list.length,
      prev: index > 0 ? list[index - 1].id : undefined,
      next: index >= 0 && index < list.length - 1 ? list[index + 1].id : undefined,
      nextPending: after.find((m) => m.id !== id && reviewState(m) === 'PENDING')?.id,
    };
  });

  ngOnInit(): void {
    this.sub = this.route.paramMap.subscribe((p) => {
      const id = Number(p.get('id'));
      this.resetView();
      this.load(id);
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
    clearTimeout(this.pollTimer);
  }

  private resetView(): void {
    clearTimeout(this.pollTimer);
    this.detail = null;
    this.selectedTab = 0;
    this.pdfPage = {};
    this.highlight = null;
    this.highlightedFieldId = null;
    this.fieldFilter = 'ALL';
    this.expandedAudit.clear();
  }

  load(id: number, silent = false): void {
    if (!silent) this.loading = true;
    this.api.getDetail(id).subscribe({
      next: (d) => {
        const keepEdits = silent && this.detail?.id === d.id && this.isDirty();
        this.detail = d;
        if (!keepEdits) this.hydrate(d);
        this.loading = false;
        this.currentId.set(d.id);
        this.currentIsUpload.set(d.sourceType === 'UPLOAD');
        clearTimeout(this.pollTimer);
        if (this.isProcessing()) {
          this.pollTimer = setTimeout(() => this.load(id, true), PROCESSING_POLL_MS);
        }
      },
      error: (err) => {
        this.loading = false;
        this.snackBar.open(err?.status === 404 ? 'Message not found' : 'Failed to load message', 'Dismiss', {
          duration: 4000,
          panelClass: 'snack-err',
        });
      },
    });
  }

  private hydrate(d: MessageDetailDto): void {
    this.classifications = d.classifications.map((c) => ({
      ...c,
      uiDecision: c.reviewerStatus === 'PENDING' ? 'ACCEPTED' : (c.reviewerStatus as Decision),
      uiOverrideCategory: c.reviewerCategory ?? c.category,
    }));
    this.fields = d.extractedFields.map((f) => ({
      ...f,
      uiDecision: f.reviewerStatus === 'OVERRIDDEN' ? 'OVERRIDDEN' : 'ACCEPTED',
      uiOverrideValue: f.reviewerValue ?? f.fieldValue,
      editing: false,
    }));
    this.literatureCases = d.literatureCases.map((lc) => ({
      ...lc,
      uiDecision: lc.reviewerStatus === 'PENDING' ? 'ACCEPTED' : (lc.reviewerStatus as Decision),
      uiOverrideIsRelevant: lc.reviewerIsRelevant ?? lc.relevantCase,
    }));
    this.rebuildGroups();
  }

  // ---------- derived state ----------

  isProcessing(): boolean {
    return !!this.detail && this.detail.status !== 'PROCESSED' && this.detail.status !== 'FAILED';
  }

  isUpload(): boolean {
    return this.detail?.sourceType === 'UPLOAD';
  }

  reviewInfo(): { reviewed: boolean; by: string | null; at: string | null } {
    const items = [...(this.detail?.classifications ?? []), ...(this.detail?.literatureCases ?? [])];
    const reviewed = items.length > 0 && items.every((i) => i.reviewerStatus !== 'PENDING');
    const last = items
      .filter((i) => i.reviewedAt)
      .sort((a, b) => Date.parse(b.reviewedAt!) - Date.parse(a.reviewedAt!))[0];
    return { reviewed, by: last?.reviewedBy ?? null, at: last?.reviewedAt ?? null };
  }

  rebuildGroups(): void {
    const top = this.fields.filter((f) => f.caseNumber == null);
    this.groups = this.toGroups(top);
  }

  private toGroups(fields: UiField[]): FieldGroupView[] {
    const keys = [...new Set(fields.map((f) => f.fieldGroup))].sort((a, b) => groupMeta(a).order - groupMeta(b).order);
    return keys.map((key) => {
      const all = fields.filter((f) => f.fieldGroup === key);
      return {
        key,
        label: groupMeta(key).label,
        icon: groupMeta(key).icon,
        fields: all,
        stated: all.filter((f) => !isNotStated(f.fieldValue)).length,
      };
    });
  }

  groupsForCase(caseNumber: number): FieldGroupView[] {
    return this.toGroups(this.fields.filter((f) => f.caseNumber === caseNumber));
  }

  needsAttention(f: UiField): boolean {
    return isNotStated(f.fieldValue) || confidenceBand(f.confidence) === 'low' || confidenceBand(f.confidence) === 'medium';
  }

  visible(f: UiField): boolean {
    if (this.fieldFilter === 'STATED') return !isNotStated(f.fieldValue);
    if (this.fieldFilter === 'ATTENTION') return this.needsAttention(f);
    return true;
  }

  hasVisibleFacts(): boolean {
    return this.groups.some((g) => this.visibleCount(g) > 0);
  }

  toggleTranslation(id: number): void {
    this.showTranslation.has(id) ? this.showTranslation.delete(id) : this.showTranslation.add(id);
  }

  toggleSummary(id: number): void {
    this.expandedSummary.has(id) ? this.expandedSummary.delete(id) : this.expandedSummary.add(id);
  }

  visibleCount(g: FieldGroupView): number {
    return g.fields.filter((f) => this.visible(f)).length;
  }

  fieldStats() {
    const top = this.fields;
    return {
      total: top.length,
      stated: top.filter((f) => !isNotStated(f.fieldValue)).length,
      attention: top.filter((f) => this.needsAttention(f)).length,
      lowConf: top.filter((f) => confidenceBand(f.confidence) === 'low').length,
    };
  }

  decisionCounts() {
    const all = [...this.classifications, ...this.fields, ...this.literatureCases] as { uiDecision: Decision }[];
    return {
      accepted: all.filter((x) => x.uiDecision === 'ACCEPTED').length,
      overridden: all.filter((x) => x.uiDecision === 'OVERRIDDEN').length,
      rejected: all.filter((x) => x.uiDecision === 'REJECTED').length,
      total: all.length,
    };
  }

  /** True when the reviewer changed something relative to what is saved on the server. */
  isDirty(): boolean {
    const saved = (status: string): Decision => (status === 'PENDING' ? 'ACCEPTED' : (status as Decision));
    return (
      this.classifications.some(
        (c) =>
          c.uiDecision !== saved(c.reviewerStatus) ||
          (c.uiDecision === 'OVERRIDDEN' && c.uiOverrideCategory !== (c.reviewerCategory ?? c.category)),
      ) ||
      this.fields.some(
        (f) =>
          f.uiDecision !== (f.reviewerStatus === 'OVERRIDDEN' ? 'OVERRIDDEN' : 'ACCEPTED') ||
          (f.uiDecision === 'OVERRIDDEN' && f.uiOverrideValue !== (f.reviewerValue ?? f.fieldValue)),
      ) ||
      this.literatureCases.some(
        (c) =>
          c.uiDecision !== saved(c.reviewerStatus) ||
          (c.uiDecision === 'OVERRIDDEN' && c.uiOverrideIsRelevant !== (c.reviewerIsRelevant ?? c.relevantCase)),
      )
    );
  }

  // ---------- document viewer ----------

  attachmentById(id: number | null): AttachmentDto | undefined {
    return this.detail?.attachments.find((a) => a.id === id);
  }

  tablesFor(attachmentId: number): TableDto[] {
    return (this.detail?.tables ?? []).filter((t) => t.attachmentId === attachmentId);
  }

  imagesFor(attachmentId: number): ImageDto[] {
    return (this.detail?.images ?? []).filter((i) => i.attachmentId === attachmentId);
  }

  private tableCache = new Map<number, { headers: string[]; rows: string[][] }>();

  parseTable(t: TableDto): { headers: string[]; rows: string[][] } {
    if (!this.tableCache.has(t.id)) {
      let parsed = { headers: [] as string[], rows: [] as string[][] };
      try {
        parsed = JSON.parse(t.tableJson);
      } catch {
        /* leave empty */
      }
      this.tableCache.set(t.id, parsed);
    }
    return this.tableCache.get(t.id)!;
  }

  pdfUrl(att: AttachmentDto): SafeResourceUrl {
    const page = this.pdfPage[att.id];
    const key = `${att.id}#${page ?? ''}`;
    if (!this.safeUrlCache.has(key)) {
      const url = this.api.attachmentFileUrl(att.id) + (page ? `#page=${page}` : '');
      this.safeUrlCache.set(key, this.sanitizer.bypassSecurityTrustResourceUrl(url));
    }
    return this.safeUrlCache.get(key)!;
  }

  rawPdfUrl(att: AttachmentDto): string {
    return this.api.attachmentFileUrl(att.id);
  }

  sourceLabel(f: ExtractedFieldDto): string {
    if (f.sourceType === 'EMAIL_BODY') return 'Email body';
    const att = this.attachmentById(f.attachmentId);
    const page = this.pageOf(f);
    return `${att?.filename ?? 'Attachment ' + f.attachmentId}${page ? ' · p.' + page : f.sourceRef ? ' · ' + f.sourceRef : ''}`;
  }

  private pageOf(f: ExtractedFieldDto): number | null {
    const m = f.sourceRef?.match(/(\d+)/);
    return m ? Number(m[1]) : null;
  }

  /** Traceability: open the exact source (email body or PDF page) a fact was extracted from. */
  jumpToSource(f: UiField): void {
    if (!this.detail) return;
    this.highlightedFieldId = f.id;
    if (f.sourceType === 'EMAIL_BODY' || f.attachmentId == null) {
      this.selectedTab = 0;
      this.highlight = isNotStated(f.fieldValue) ? null : f.fieldValue;
      if (this.highlight && !this.bodySegments().some((s) => s.hit)) {
        this.snackBar.open('Value is paraphrased — no exact match in the email text', undefined, { duration: 2500 });
      }
      return;
    }
    const idx = this.detail.attachments.findIndex((a) => a.id === f.attachmentId);
    if (idx >= 0) {
      const page = this.pageOf(f);
      if (page) this.pdfPage = { ...this.pdfPage, [f.attachmentId]: page };
      this.selectedTab = idx + (this.isUpload() ? 0 : 1);
    }
  }

  private segmentCache: { key: string; segs: Segment[] } | null = null;

  bodySegments(): Segment[] {
    const body = this.detail?.bodyText ?? '';
    const term = this.highlight?.trim();
    const key = `${this.detail?.id}|${term ?? ''}`;
    if (this.segmentCache?.key === key) return this.segmentCache.segs;
    let segs: Segment[] = [{ text: body, hit: false }];
    if (term && term.length >= 2) {
      const escaped = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const parts = body.split(new RegExp(`(${escaped})`, 'gi'));
      segs = parts.filter((p) => p !== '').map((p) => ({ text: p, hit: p.toLowerCase() === term.toLowerCase() }));
    }
    this.segmentCache = { key, segs };
    return segs;
  }

  // ---------- review actions ----------

  resetChanges(): void {
    if (this.detail) this.hydrate(this.detail);
  }

  editField(f: UiField): void {
    f.editing = true;
    if (f.uiDecision !== 'OVERRIDDEN') f.uiOverrideValue = isNotStated(f.fieldValue) ? '' : f.fieldValue;
  }

  confirmEdit(f: UiField): void {
    f.editing = false;
    const value = f.uiOverrideValue.trim();
    if (!value || value === f.fieldValue) {
      f.uiDecision = 'ACCEPTED';
      f.uiOverrideValue = f.fieldValue;
    } else {
      f.uiDecision = 'OVERRIDDEN';
      f.uiOverrideValue = value;
    }
  }

  revertField(f: UiField): void {
    f.editing = false;
    f.uiDecision = 'ACCEPTED';
    f.uiOverrideValue = f.fieldValue;
  }

  @HostListener('window:keydown', ['$event'])
  onKey(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      this.submitReview();
    }
  }

  submitReview(goNext = false): void {
    if (!this.detail || this.submitting || this.isProcessing()) return;
    if (this.fields.some((f) => f.editing)) {
      this.fields.filter((f) => f.editing).forEach((f) => this.confirmEdit(f));
    }
    this.submitting = true;
    const request: ReviewRequest = {
      reviewerName: this.reviewer.name(),
      classifications: this.classifications.map((c) => ({
        classificationId: c.id,
        decision: c.uiDecision,
        overrideCategory: c.uiDecision === 'OVERRIDDEN' ? c.uiOverrideCategory : null,
      })),
      fields: this.fields.map((f) => ({
        fieldId: f.id,
        decision: f.uiDecision,
        overrideValue: f.uiDecision === 'OVERRIDDEN' ? f.uiOverrideValue : null,
      })),
      literatureCases: this.literatureCases.map((lc) => ({
        caseId: lc.id,
        decision: lc.uiDecision,
        overrideIsRelevant: lc.uiDecision === 'OVERRIDDEN' ? lc.uiOverrideIsRelevant : null,
      })),
    };
    const id = this.detail.id;
    this.api.submitReview(id, request).subscribe({
      next: (d) => {
        this.submitting = false;
        this.detail = d;
        this.hydrate(d);
        this.store.refresh(true);
        const next = this.position().nextPending;
        if (goNext && next) {
          this.snackBar.open('Review saved — opening next pending message', undefined, { duration: 2500, panelClass: 'snack-ok' });
          this.router.navigate(['/messages', next]);
          return;
        }
        const ref = this.snackBar.open('Review saved to the audit trail', next ? 'Next pending' : 'Dismiss', {
          duration: 5000,
          panelClass: 'snack-ok',
        });
        if (next) ref.onAction().subscribe(() => this.router.navigate(['/messages', next]));
      },
      error: (err) => {
        this.submitting = false;
        this.snackBar.open('Failed to save review: ' + (err?.error?.error ?? err.message), 'Dismiss', {
          duration: 6000,
          panelClass: 'snack-err',
        });
      },
    });
  }

  back(): void {
    this.router.navigate([this.isUpload() ? '/literature' : '/']);
  }

  // ---------- audit trail ----------

  auditEntries(): AuditEntryDto[] {
    const all = [...(this.detail?.auditTrail ?? [])].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
    if (this.auditFilter === 'AI') return all.filter((a) => a.actor === 'system');
    if (this.auditFilter === 'REVIEWER') return all.filter((a) => a.actor !== 'system');
    return all;
  }

  auditIcon(a: AuditEntryDto): string {
    if (a.action.startsWith('REVIEWER_ACCEPTED')) return 'check_circle';
    if (a.action.startsWith('REVIEWER_OVERRIDDEN')) return 'edit';
    if (a.action.startsWith('REVIEWER_REJECTED')) return 'cancel';
    if (a.action.includes('FAIL') || a.action.includes('ERROR')) return 'error';
    if (a.action.includes('INGEST')) return 'move_to_inbox';
    if (a.action.includes('PDF')) return 'picture_as_pdf';
    if (a.action.includes('CLASSIF')) return 'category';
    if (a.action.includes('EXTRACT')) return 'data_object';
    return a.actor === 'system' ? 'smart_toy' : 'person';
  }

  auditTitle(a: AuditEntryDto): string {
    if (a.action.startsWith('AI_')) return 'AI ' + humanize(a.action.slice(3)).toLowerCase();
    return humanize(a.action.replace(/^REVIEWER_/, ''));
  }

  auditSummary(a: AuditEntryDto): string {
    const d = this.parseJson(a.detailJson);
    if (!d) return '';
    const bits: string[] = [];
    if (d['category']) bits.push(String(d['category']));
    if (d['fieldName']) bits.push(humanize(String(d['fieldName'])));
    if (d['flavor']) bits.push(String(d['flavor']));
    if (d['overrideCategory']) bits.push('→ ' + d['overrideCategory']);
    if (d['overrideValue'] != null) bits.push('→ "' + d['overrideValue'] + '"');
    if (typeof d['confidence'] === 'number') bits.push(Math.round((d['confidence'] as number) * 100) + '%');
    return bits.join(' · ');
  }

  prettyJson(json: string | null): string {
    const d = this.parseJson(json);
    return d ? JSON.stringify(d, null, 2) : json ?? '';
  }

  private parseJson(json: string | null): Record<string, unknown> | null {
    if (!json) return null;
    try {
      return JSON.parse(json);
    } catch {
      return null;
    }
  }

  toggleAudit(id: number): void {
    this.expandedAudit.has(id) ? this.expandedAudit.delete(id) : this.expandedAudit.add(id);
  }

  trackById(_: number, item: { id: number }): number {
    return item.id;
  }
}
