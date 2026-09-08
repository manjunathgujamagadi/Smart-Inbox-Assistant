import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatToolbarModule } from '@angular/material/toolbar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { MessageApiService } from '../../core/services/message-api.service';
import {
  AttachmentDto,
  Category,
  ClassificationDto,
  ExtractedFieldDto,
  LiteratureCaseDto,
  MessageDetailDto,
  ReviewRequest,
} from '../../core/models/message.model';

interface UiClassification extends ClassificationDto {
  uiDecision: 'ACCEPTED' | 'OVERRIDDEN' | 'REJECTED';
  uiOverrideCategory: Category;
}

interface UiField extends ExtractedFieldDto {
  uiDecision: 'ACCEPTED' | 'OVERRIDDEN';
  uiOverrideValue: string;
}

interface UiLiteratureCase extends LiteratureCaseDto {
  uiDecision: 'ACCEPTED' | 'OVERRIDDEN' | 'REJECTED';
  uiOverrideIsRelevant: boolean;
}

const CATEGORIES: Category[] = ['ICSR', 'PQC', 'MI', 'NOT_RELEVANT'];

@Component({
  selector: 'app-message-detail',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatToolbarModule,
    MatCardModule,
    MatChipsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatSelectModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatDividerModule,
    MatExpansionModule,
    MatTabsModule,
    MatProgressBarModule,
  ],
  templateUrl: './message-detail.component.html',
  styleUrl: './message-detail.component.scss',
})
export class MessageDetailComponent implements OnInit {
  private api = inject(MessageApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private snackBar = inject(MatSnackBar);
  private sanitizer = inject(DomSanitizer);
  private safeUrlCache = new Map<number, SafeResourceUrl>();

  categories = CATEGORIES;
  detail: MessageDetailDto | null = null;
  classifications: UiClassification[] = [];
  fields: UiField[] = [];
  fieldGroups: string[] = [];
  literatureCases: UiLiteratureCase[] = [];
  loading = true;
  submitting = false;
  selectedAttachment: AttachmentDto | null = null;

  ngOnInit(): void {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.load(id);
  }

  load(id: number): void {
    this.loading = true;
    this.api.getDetail(id).subscribe({
      next: (d) => {
        this.detail = d;
        this.classifications = d.classifications.map((c) => ({
          ...c,
          uiDecision: c.reviewerStatus === 'PENDING' ? 'ACCEPTED' : (c.reviewerStatus as any),
          uiOverrideCategory: c.reviewerCategory ?? c.category,
        }));
        this.fields = d.extractedFields.map((f) => ({
          ...f,
          uiDecision: f.reviewerStatus === 'PENDING' ? 'ACCEPTED' : (f.reviewerStatus as any),
          uiOverrideValue: f.reviewerValue ?? f.fieldValue,
        }));
        this.fieldGroups = [...new Set(this.fields.filter((f) => f.caseNumber == null).map((f) => f.fieldGroup))];
        this.literatureCases = d.literatureCases.map((lc) => ({
          ...lc,
          uiDecision: lc.reviewerStatus === 'PENDING' ? 'ACCEPTED' : (lc.reviewerStatus as any),
          uiOverrideIsRelevant: lc.reviewerIsRelevant ?? lc.relevantCase,
        }));
        this.selectedAttachment = d.attachments.find((a) => a.processable) ?? null;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.snackBar.open('Failed to load message', 'Dismiss', { duration: 4000 });
      },
    });
  }

  fieldsFor(group: string): UiField[] {
    return this.fields.filter((f) => f.fieldGroup === group && f.caseNumber == null);
  }

  fieldsForCase(caseNumber: number): UiField[] {
    return this.fields.filter((f) => f.caseNumber === caseNumber);
  }

  fieldGroupsForCase(caseNumber: number): string[] {
    return [...new Set(this.fieldsForCase(caseNumber).map((f) => f.fieldGroup))];
  }

  casesFor(attachmentId: number): UiLiteratureCase[] {
    return this.literatureCases.filter((c) => c.attachmentId === attachmentId);
  }

  tablesFor(attachmentId: number) {
    return (this.detail?.tables ?? []).filter((t) => t.attachmentId === attachmentId);
  }

  imagesFor(attachmentId: number) {
    return (this.detail?.images ?? []).filter((i) => i.attachmentId === attachmentId);
  }

  parseTable(json: string): { headers: string[]; rows: string[][] } {
    try {
      return JSON.parse(json);
    } catch {
      return { headers: [], rows: [] };
    }
  }

  safeAttachmentUrl(id: number): SafeResourceUrl {
    if (!this.safeUrlCache.has(id)) {
      this.safeUrlCache.set(id, this.sanitizer.bypassSecurityTrustResourceUrl(this.api.attachmentFileUrl(id)));
    }
    return this.safeUrlCache.get(id)!;
  }

  sourceLabel(f: ExtractedFieldDto): string {
    if (f.sourceType === 'EMAIL_BODY') {
      return 'email body';
    }
    const att = this.detail?.attachments.find((a) => a.id === f.attachmentId);
    return `${att?.filename ?? 'attachment ' + f.attachmentId}${f.sourceRef ? ' — ' + f.sourceRef : ''}`;
  }

  submitReview(): void {
    if (!this.detail) return;
    this.submitting = true;
    const request: ReviewRequest = {
      reviewerName: 'demo-reviewer',
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
    this.api.submitReview(this.detail.id, request).subscribe({
      next: () => {
        this.submitting = false;
        this.snackBar.open('Review saved', 'Dismiss', { duration: 3000 });
        this.load(this.detail!.id);
      },
      error: (err) => {
        this.submitting = false;
        this.snackBar.open('Failed to save review: ' + (err?.error?.error ?? err.message), 'Dismiss', {
          duration: 6000,
        });
      },
    });
  }

  back(): void {
    this.router.navigate(['/']);
  }
}
