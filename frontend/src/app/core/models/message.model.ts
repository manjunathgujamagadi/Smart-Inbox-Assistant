export type Category = 'ICSR' | 'PQC' | 'MI' | 'NOT_RELEVANT';
export type ReviewerStatus = 'PENDING' | 'ACCEPTED' | 'OVERRIDDEN' | 'REJECTED';
export type SourceType = 'EMAIL_BODY' | 'PDF_PAGE';
export type PdfFlavor = 'DIGITAL' | 'SCANNED_HANDWRITTEN' | 'ARTICLE' | 'NON_ENGLISH';

export interface ClassificationDto {
  id: number;
  category: Category;
  confidence: number;
  reason: string;
  reviewerStatus: ReviewerStatus;
  reviewerCategory: Category | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
}

export interface ExtractedFieldDto {
  id: number;
  attachmentId: number | null;
  caseNumber: number | null;
  fieldGroup: string;
  fieldName: string;
  fieldValue: string;
  confidence: number | null;
  sourceType: SourceType;
  sourceRef: string | null;
  reviewerStatus: ReviewerStatus;
  reviewerValue: string | null;
}

export interface LiteratureCaseDto {
  id: number;
  attachmentId: number;
  caseNumber: number;
  relevantCase: boolean;
  relevanceReason: string;
  confidence: number;
  summary: string;
  reviewerStatus: ReviewerStatus;
  reviewerIsRelevant: boolean | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
}

export interface AttachmentDto {
  id: number;
  filename: string;
  mimeType: string | null;
  processable: boolean;
  pdfFlavor: PdfFlavor | null;
  detectedLanguage: string | null;
  pageCount: number | null;
  processed: boolean;
  summary: string | null;
}

export interface TableDto {
  id: number;
  attachmentId: number;
  pageNumber: number;
  tableJson: string; // JSON string: { headers: string[], rows: string[][] }
}

export interface ImageDto {
  id: number;
  attachmentId: number;
  pageNumber: number;
  description: string;
  flaggedForReview: boolean;
}

export interface AuditEntryDto {
  id: number;
  entityType: string;
  entityId: number;
  actor: string;
  action: string;
  detailJson: string | null;
  createdAt: string;
}

export interface MessageSummaryDto {
  id: number;
  sourceType: string;
  sender: string | null;
  subject: string | null;
  receivedAt: string | null;
  status: string;
  classifications: ClassificationDto[];
  literatureCases: LiteratureCaseDto[];
}

export interface MessageDetailDto {
  id: number;
  sourceType: string;
  sender: string | null;
  subject: string | null;
  receivedAt: string | null;
  bodyText: string | null;
  status: string;
  attachments: AttachmentDto[];
  classifications: ClassificationDto[];
  extractedFields: ExtractedFieldDto[];
  tables: TableDto[];
  images: ImageDto[];
  literatureCases: LiteratureCaseDto[];
  auditTrail: AuditEntryDto[];
}

export interface ClassificationReview {
  classificationId: number;
  decision: 'ACCEPTED' | 'OVERRIDDEN' | 'REJECTED';
  overrideCategory?: Category | null;
}

export interface FieldReview {
  fieldId: number;
  decision: 'ACCEPTED' | 'OVERRIDDEN';
  overrideValue?: string | null;
}

export interface LiteratureCaseReview {
  caseId: number;
  decision: 'ACCEPTED' | 'OVERRIDDEN' | 'REJECTED';
  overrideIsRelevant?: boolean | null;
}

export interface ReviewRequest {
  reviewerName: string;
  classifications: ClassificationReview[];
  fields: FieldReview[];
  literatureCases: LiteratureCaseReview[];
}

export interface BatchReportJobEntry {
  messageId: number;
  subject: string | null;
  status: string;
  durationMs: number | null;
}

export interface BatchReportDto {
  totalDocuments: number;
  done: number;
  failed: number;
  avgDurationMs: number;
  minDurationMs: number;
  maxDurationMs: number;
  jobs: BatchReportJobEntry[];
}
