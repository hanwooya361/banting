-- T20 피드백 버튼: 어느 화면에서 남긴 문의인지 (버그 재현용). 우리 사이트 경로만 (/로 시작, // 아님). 문의 화면에서 직접 쓰면 NULL
ALTER TABLE inquiries ADD COLUMN page_path VARCHAR(200) NULL AFTER content;
