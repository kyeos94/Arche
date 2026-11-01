import { Navigate, Route, Routes, useLocation } from 'react-router'
import { AlertsPage } from '@/features/collection/AlertsPage'
import { Layout } from '@/components/Layout'
import { AdminPage } from '@/features/admin/AdminPage'
import { HolidayAdminPage } from '@/features/admin/HolidayAdminPage'
import { MenuAdminPage } from '@/features/admin/MenuAdminPage'
import { NotesPage } from '@/features/notes/NotesPage'
import { PermissionAdminPage } from '@/features/admin/PermissionAdminPage'
import { useAuth } from '@/features/auth/auth'
import { LoginPage } from '@/features/auth/LoginPage'
import { PasswordChangePage } from '@/features/auth/PasswordChangePage'
import { BulkPage } from '@/features/bulk/BulkPage'
import { LoanDetailPage } from '@/features/loans/LoanDetailPage'
import { LoanListPage } from '@/features/loans/LoanListPage'
import { NewLoanPage } from '@/features/loans/NewLoanPage'
import { AdjustmentsPage, ChargeOffCandidatesPage } from '@/features/lifecycle/pages'
import { CorrectionsPage } from '@/features/payments/CorrectionsPage'
import { SplitPage } from '@/features/payments/SplitPage'
import { LegalActionsPage } from '@/features/collection/LegalActionsPage'
import { TemplatesPage } from '@/features/communication/TemplatesPage'
import { CreditReportsPage } from '@/features/creditinfo/CreditReportsPage'
import { WithdrawalsPage } from '@/features/treasury/WithdrawalsPage'
import { UnmatchedPage } from '@/features/payments/UnmatchedPage'
import { ReportsPage } from '@/features/reports/ReportsPage'
import { ApplicationDetailPage } from '@/features/origination/ApplicationDetailPage'
import { ApplicationListPage } from '@/features/origination/ApplicationListPage'
import { BrokersPage } from '@/features/origination/BrokersPage'
import { LandingPage, MockIdentityPage } from '@/features/origination/LandingPage'
import { PlatformSimulatorPage } from '@/features/origination/PlatformSimulatorPage'
import { ProductsPage } from '@/features/origination/ProductsPage'
import { UnderwritersPage } from '@/features/origination/UnderwritersPage'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

/** 로그인 없이 여는 고객·Mock 기관 화면 (guide 11.3) */
const PUBLIC_PREFIXES = ['/landing/', '/mock-identity/']

export function App() {
  const { pathname } = useLocation()
  if (PUBLIC_PREFIXES.some((p) => pathname.startsWith(p))) {
    return (
      <Routes>
        <Route path="landing/:token" element={<LandingPage />} />
        <Route path="mock-identity/:tx" element={<MockIdentityPage />} />
      </Routes>
    )
  }
  return <StaffApp />
}

function StaffApp() {
  const { me, loading, can } = useAuth()
  if (loading) return <div className="p-6 text-xs text-muted-foreground">불러오는 중…</div>
  if (!me) return <LoginPage />
  if (me.passwordChangeRequired) return <PasswordChangePage forced />

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route
          index
          element={<Navigate to={!can('loan.read') && can('application.review') ? '/applications' : '/loans'} replace />}
        />
        <Route path="loans" element={<LoanListPage />} />
        <Route path="loans/new" element={<NewLoanPage />} />
        <Route path="loans/:id" element={<LoanDetailPage />} />
        <Route path="alerts" element={<AlertsPage />} />
        <Route path="unmatched" element={<UnmatchedPage />} />
        <Route path="corrections" element={<CorrectionsPage />} />
        <Route path="payment-splits" element={<SplitPage />} />
        <Route path="legal-actions" element={<LegalActionsPage />} />
        <Route path="charge-off" element={<ChargeOffCandidatesPage />} />
        <Route path="adjustments" element={<AdjustmentsPage />} />
        <Route path="bulk" element={<BulkPage />} />
        <Route path="reports" element={<ReportsPage />} />
        <Route path="admin" element={<AdminPage />} />
        <Route path="admin/menus" element={<MenuAdminPage />} />
        <Route path="admin/permissions" element={<PermissionAdminPage />} />
        <Route path="admin/holidays" element={<HolidayAdminPage />} />
        <Route path="notes" element={<NotesPage />} />
        <Route path="withdrawals" element={<WithdrawalsPage />} />
        <Route path="message-templates" element={<TemplatesPage />} />
        <Route path="credit-reports" element={<CreditReportsPage />} />
        <Route path="applications" element={<ApplicationListPage />} />
        <Route path="applications/:id" element={<ApplicationDetailPage />} />
        <Route path="underwriters" element={<UnderwritersPage />} />
        <Route path="products" element={<ProductsPage />} />
        <Route path="brokers" element={<BrokersPage />} />
        <Route path="mock-platform" element={<PlatformSimulatorPage />} />
        <Route
          path="account"
          element={
            <Card className="max-w-md">
              <CardHeader>
                <CardTitle>비밀번호 변경</CardTitle>
              </CardHeader>
              <CardContent>
                <PasswordChangePage />
              </CardContent>
            </Card>
          }
        />
        <Route path="*" element={<p className="text-xs">페이지가 없습니다.</p>} />
      </Route>
    </Routes>
  )
}
