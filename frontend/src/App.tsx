import { Route, Routes } from 'react-router'
import { Layout } from './components/Layout'
import { RequireAuth } from './components/RequireAuth'
import { GroupBuyDetailPage } from './pages/GroupBuyDetailPage'
import { GroupBuyEditPage } from './pages/GroupBuyEditPage'
import { GroupBuyNewPage } from './pages/GroupBuyNewPage'
import { HomePage } from './pages/HomePage'
import { LandingPage } from './pages/LandingPage'
import { LocationPage } from './pages/LocationPage'
import { LoginPage } from './pages/LoginPage'
import { CheckoutPage } from './pages/CheckoutPage'
import { PaymentFailPage, PaymentSuccessPage } from './pages/PaymentResultPages'
import { MePage } from './pages/MePage'
import { MyGroupBuysPage } from './pages/MyGroupBuysPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { InquiriesPage } from './pages/InquiriesPage'
import { NoticesPage } from './pages/NoticesPage'
import { AdminPage } from './pages/AdminPage'
import { PrivacyPage } from './pages/PrivacyPage'
import { WithdrawPage } from './pages/WithdrawPage'
import { SignupPage } from './pages/SignupPage'

function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<LandingPage />} />
        <Route path="intro" element={<LandingPage alwaysShow />} />
        <Route path="group-buys" element={<HomePage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="signup" element={<SignupPage />} />
        <Route
          path="me"
          element={
            <RequireAuth>
              <MePage />
            </RequireAuth>
          }
        />
        <Route
          path="me/group-buys"
          element={
            <RequireAuth>
              <MyGroupBuysPage />
            </RequireAuth>
          }
        />
        <Route path="notices" element={<NoticesPage />} />
        <Route path="privacy" element={<PrivacyPage />} />
        <Route
          path="me/withdraw"
          element={
            <RequireAuth>
              <WithdrawPage />
            </RequireAuth>
          }
        />
        <Route
          path="inquiries"
          element={
            <RequireAuth>
              <InquiriesPage />
            </RequireAuth>
          }
        />
        <Route
          path="admin"
          element={
            <RequireAuth>
              <AdminPage />
            </RequireAuth>
          }
        />
        <Route
          path="notifications"
          element={
            <RequireAuth>
              <NotificationsPage />
            </RequireAuth>
          }
        />
        <Route
          path="group-buys/new"
          element={
            <RequireAuth>
              <GroupBuyNewPage />
            </RequireAuth>
          }
        />
        <Route path="group-buys/:id" element={<GroupBuyDetailPage />} />
        <Route
          path="group-buys/:id/edit"
          element={
            <RequireAuth>
              <GroupBuyEditPage />
            </RequireAuth>
          }
        />
        <Route
          path="payments/checkout"
          element={
            <RequireAuth>
              <CheckoutPage />
            </RequireAuth>
          }
        />
        <Route
          path="payments/success"
          element={
            <RequireAuth>
              <PaymentSuccessPage />
            </RequireAuth>
          }
        />
        <Route path="payments/fail" element={<PaymentFailPage />} />
        <Route
          path="me/location"
          element={
            <RequireAuth>
              <LocationPage />
            </RequireAuth>
          }
        />
      </Route>
    </Routes>
  )
}

export default App
