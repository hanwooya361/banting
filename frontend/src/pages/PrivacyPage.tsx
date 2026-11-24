import type { ReactNode } from 'react'
import { Link } from 'react-router'

// 개인정보처리방침 (T18). 실제 사람이 가입하기 전에 무엇을 왜 모으고 언제 지우는지 알린다.
// 코드에서 실제로 저장하는 것과 맞춰 쓴다 (tech-design ERD·infra.md). 바뀌면 시행일을 고친다
const EFFECTIVE_DATE = '2026년 11월 24일'

export function PrivacyPage() {
  return (
    <article className="flex flex-col gap-6 text-sm leading-relaxed text-gray-800">
      <header>
        <h1 className="text-xl font-bold">개인정보처리방침</h1>
        <p className="mt-1 text-gray-500">시행일 {EFFECTIVE_DATE} · 반띵은 개인 포트폴리오로 운영하는 베타 서비스예요.</p>
      </header>

      <Section title="1. 모으는 정보와 이유">
        <table className="w-full border-collapse text-left">
          <thead>
            <tr className="border-b border-gray-200 text-gray-500">
              <th className="py-2 pr-2 font-medium">정보</th>
              <th className="py-2 font-medium">이유</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            <Row item="이메일, 비밀번호(복원할 수 없게 암호화), 닉네임" why="가입·로그인, 공구에서 서로 알아보기" />
            <Row
              item="동네 좌표(약 100m 단위로 반올림)와 동네 이름"
              why="가까운 공구 찾기. 다른 사람에게는 동네 이름만 보여요"
            />
            <Row item="공구 개설·참여·결제 기록(주문번호, 금액)" why="공구 진행, 자동 환불, 정산 기록" />
            <Row item="문의 내용" why="답변과 서비스 개선" />
            <Row item="로그인 유지용 쿠키" why="다시 로그인하지 않도록 (HttpOnly, 14일)" />
          </tbody>
        </table>
        <p className="mt-2 text-gray-600">카드 번호 같은 결제 수단 정보는 반띵이 받거나 저장하지 않아요.</p>
      </Section>

      <Section title="2. 다른 곳에 맡기거나 보내는 정보">
        <ul className="list-disc pl-5">
          <li>
            <strong>토스페이먼츠</strong>: 결제 처리. 지금은 <strong>테스트 결제</strong>라 실제 돈이 오가지 않아요
          </li>
          <li>
            <strong>카카오</strong>: 동네 설정 때 좌표를 보내 동네 이름을 받아 와요(저장은 반띵에만)
          </li>
          <li>
            <strong>Amazon Web Services</strong>: 서버와 데이터 보관. 서버가 <strong>호주(시드니)</strong>에 있어 위 정보가
            국외에 저장돼요
          </li>
        </ul>
        <p className="mt-2">그 밖에 개인정보를 팔거나 다른 사람에게 주지 않아요.</p>
      </Section>

      <Section title="3. 보관 기간">
        <ul className="list-disc pl-5">
          <li>회원 정보: 탈퇴할 때까지</li>
          <li>알림: 30일 뒤 자동 삭제</li>
          <li>데이터베이스 백업: 14일 뒤 자동 삭제</li>
          <li>결제·정산 기록: 거래 이력 확인을 위해 탈퇴 후에도 회원과 연결되지 않게 남길 수 있어요</li>
        </ul>
      </Section>

      <Section title="4. 내 정보 보기·고치기·탈퇴">
        <p>
          내 정보 화면에서 확인하고 동네를 바꿀 수 있어요. 탈퇴는 내 정보 화면 맨 아래 <strong>탈퇴하기</strong>에서 바로 할 수
          있어요(진행 중인 공구가 없을 때). 탈퇴하면 이메일·닉네임·비밀번호·동네 정보와 알림이 지워지고, 거래 기록은 누구인지
          알 수 없게 남아요. 그 밖의 정보 삭제 요청은{' '}
          <Link to="/inquiries" className="underline">
            문의·피드백
          </Link>
          으로 받아요.
        </p>
      </Section>

      <Section title="5. 문의">
        <p>
          개인정보에 관한 문의도{' '}
          <Link to="/inquiries" className="underline">
            문의·피드백
          </Link>
          으로 받아요.
        </p>
      </Section>
    </article>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h2 className="mb-2 font-semibold text-gray-900">{title}</h2>
      {children}
    </section>
  )
}

function Row({ item, why }: { item: string; why: string }) {
  return (
    <tr className="align-top">
      <td className="py-2 pr-2">{item}</td>
      <td className="py-2 text-gray-600">{why}</td>
    </tr>
  )
}
