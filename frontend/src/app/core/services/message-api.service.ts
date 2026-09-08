import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  BatchReportDto,
  MessageDetailDto,
  MessageSummaryDto,
  ReviewRequest,
} from '../models/message.model';

@Injectable({ providedIn: 'root' })
export class MessageApiService {
  private readonly base = environment.apiBaseUrl;

  constructor(private http: HttpClient) {}

  listQueue(): Observable<MessageSummaryDto[]> {
    return this.http.get<MessageSummaryDto[]>(`${this.base}/messages`);
  }

  getDetail(id: number): Observable<MessageDetailDto> {
    return this.http.get<MessageDetailDto>(`${this.base}/messages/${id}`);
  }

  submitReview(id: number, request: ReviewRequest): Observable<MessageDetailDto> {
    return this.http.post<MessageDetailDto>(`${this.base}/messages/${id}/review`, request);
  }

  runBatch(dir?: string): Observable<{ ingested: number; messageIds: number[] }> {
    const params = dir ? `?dir=${encodeURIComponent(dir)}` : '';
    return this.http.post<{ ingested: number; messageIds: number[] }>(`${this.base}/batch/run${params}`, {});
  }

  getBatchReport(): Observable<BatchReportDto> {
    return this.http.get<BatchReportDto>(`${this.base}/batch/report`);
  }

  attachmentFileUrl(attachmentId: number): string {
    return `${this.base}/attachments/${attachmentId}/file`;
  }

  uploadLiterature(files: File[]): Observable<{ uploaded: number; messageIds: number[] }> {
    const formData = new FormData();
    for (const file of files) {
      formData.append('files', file, file.name);
    }
    return this.http.post<{ uploaded: number; messageIds: number[] }>(`${this.base}/literature/upload`, formData);
  }
}
