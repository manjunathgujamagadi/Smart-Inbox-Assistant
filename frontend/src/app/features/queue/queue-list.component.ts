import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink } from '@angular/router';
import { MessageApiService } from '../../core/services/message-api.service';
import { MessageSummaryDto } from '../../core/models/message.model';

@Component({
  selector: 'app-queue-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatTableModule,
    MatChipsModule,
    MatIconModule,
    MatButtonModule,
    MatProgressBarModule,
    MatToolbarModule,
  ],
  templateUrl: './queue-list.component.html',
  styleUrl: './queue-list.component.scss',
})
export class QueueListComponent implements OnInit {
  private api = inject(MessageApiService);
  private snackBar = inject(MatSnackBar);

  messages: MessageSummaryDto[] = [];
  loading = true;
  running = false;
  uploading = false;
  displayedColumns = ['sender', 'subject', 'received', 'status', 'classifications', 'actions'];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.api.listQueue().subscribe({
      next: (data) => {
        this.messages = data;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.snackBar.open('Failed to load message queue', 'Dismiss', { duration: 4000 });
      },
    });
  }

  runBatch(): void {
    this.running = true;
    this.api.runBatch().subscribe({
      next: (res) => {
        this.running = false;
        this.snackBar.open(`Ingested ${res.ingested} document(s) - processing in background`, 'Dismiss', {
          duration: 4000,
        });
        setTimeout(() => this.load(), 1500);
      },
      error: (err) => {
        this.running = false;
        this.snackBar.open('Batch run failed: ' + (err?.error?.error ?? err.message), 'Dismiss', {
          duration: 6000,
        });
      },
    });
  }

  onFilesSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = input.files ? Array.from(input.files) : [];
    input.value = ''; // allow re-selecting the same file(s) later
    if (!files.length) return;

    this.uploading = true;
    this.api.uploadLiterature(files).subscribe({
      next: (res) => {
        this.uploading = false;
        this.snackBar.open(`Uploaded ${res.uploaded} article(s) for literature screening`, 'Dismiss', {
          duration: 4000,
        });
        setTimeout(() => this.load(), 1500);
      },
      error: (err) => {
        this.uploading = false;
        this.snackBar.open('Upload failed: ' + (err?.error?.error ?? err.message), 'Dismiss', { duration: 6000 });
      },
    });
  }

  literatureLabel(m: MessageSummaryDto): string {
    if (!m.literatureCases.length) {
      return m.status === 'PROCESSED' ? 'no cases found' : 'screening…';
    }
    const relevant = m.literatureCases.filter((c) => c.relevantCase).length;
    return `${relevant}/${m.literatureCases.length} relevant case(s)`;
  }

  chipColor(status: string): string {
    switch (status) {
      case 'ACCEPTED':
        return 'primary';
      case 'OVERRIDDEN':
        return 'accent';
      case 'REJECTED':
        return 'warn';
      default:
        return '';
    }
  }
}
