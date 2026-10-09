import { Routes } from '@angular/router';
import { QueueListComponent } from './features/queue/queue-list.component';
import { BatchReportComponent } from './features/report/batch-report.component';
import { MessageDetailComponent } from './features/review/message-detail.component';

export const routes: Routes = [
  { path: '', component: QueueListComponent, data: { mode: 'mail' }, title: 'Review queue · Smart Inbox' },
  {
    path: 'literature',
    component: QueueListComponent,
    data: { mode: 'literature' },
    title: 'Literature screening · Smart Inbox',
  },
  { path: 'report', component: BatchReportComponent, title: 'Processing report · Smart Inbox' },
  { path: 'messages/:id', component: MessageDetailComponent, title: 'Review · Smart Inbox' },
  { path: '**', redirectTo: '' },
];
