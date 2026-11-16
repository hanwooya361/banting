// CloudFront Function (viewer-request, 기본 동작 `/*`에만 연결)
// React 라우트(/groupbuys/3 처럼 확장자 없는 경로)를 index.html로 바꿔 S3에서 화면을 돌려준다.
// "404 오류 → index.html" 설정은 배포 전체에 걸려서 API의 404까지 HTML로 바뀌므로 쓰지 않는다.
function handler(event) {
  var request = event.request;
  var last = request.uri.split('/').pop();
  if (last.indexOf('.') === -1) {
    request.uri = '/index.html';
  }
  return request;
}
