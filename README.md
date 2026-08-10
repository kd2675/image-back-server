# image-back-server

이미지와 일반 첨부파일의 임시 업로드, 최종 저장, 조회를 담당하는 독립 서버입니다. 이미지는 날짜 기반 경로와 여러 크기 파생본을 만들고, 일반 첨부는 허용 확장자·콘텐츠 형식·파일 시그니처를 검증합니다.

## Runtime

- 기본 포트: `8081`
- local 저장소: `image-back-server/uploads/`
- dev/prod 저장소: `/data/image-back-server/uploads/`
- 임시 파일 보존: 기본 180분
- 첨부 최대 크기: 기본 20MB

## Public API

- `POST /upload`, `POST /upload/batch`: 기존 이미지 업로드
- `POST /upload/temp`: 이미지 임시 업로드
- `POST /files/finalize`: 이미지 최종화
- `GET /images/{*fileName}`: 이미지 조회와 선택적 동적 리사이즈
- `POST /upload/temp-file`: 일반 첨부 임시 업로드
- `GET /files/{*fileName}`: 공개 파일 조회
  - `semo/attachments/**` 최종 첨부는 이 경로에서 차단됩니다.

## Protected Attachment API

다음 API는 `X-Internal-File-Token`이 필요하며, SEMO 백엔드만 호출합니다.

- `POST /files/finalize-attachment`: 임시 첨부를 `semo/attachments/**`로 이동하고 미등록 표식 생성
- `POST /internal/files/confirm-attachment`: SEMO DB 커밋이 끝난 파일의 표식 제거
- `GET /internal/files/pending-attachments`: 고아 유예 시간이 지난 미등록 파일 조회
- `DELETE /internal/files/finalized-attachment`: DB에 등록되지 않은 고아 파일 삭제
- `GET /internal/files/content`: SEMO가 권한 검사를 마친 파일 내용 조회

`local`은 개발 기본 토큰을 사용합니다. `dev`와 `prod`는 이미지 서버와 SEMO 서버에 같은 `ATTACHMENT_INTERNAL_TOKEN`을 반드시 주입해야 합니다. 토큰이 비어 있으면 서버가 시작되지 않습니다.

## Attachment Lifecycle

1. 프론트가 `/upload/temp-file`로 임시 업로드합니다.
2. SEMO가 내부 토큰으로 파일을 최종화합니다.
3. 이미지 서버가 최종 파일 옆에 `.pending-claim` 표식을 만듭니다.
4. SEMO DB 트랜잭션이 커밋되면 표식을 제거하고, 롤백되면 최종 파일을 삭제합니다.
5. 프로세스 중단으로 표식이 남으면 SEMO 조정 작업이 DB 존재 여부에 따라 확정하거나 삭제합니다.
6. SEMO에서 소프트 삭제된 첨부는 감사·보존 대상이므로 DB 행과 물리 파일을 유지합니다.

이 구조는 최종화와 DB 저장 사이의 분산 트랜잭션 공백을 추적 가능한 상태로 바꾸고, 정상 파일과 고아 파일을 구분합니다.

## Key Paths

- `src/main/java/image/back/server/controller/ImageController.java`
- `src/main/java/image/back/server/service/ImageServiceImpl.java`
- `src/main/resources/application.yaml`
- `src/main/resources/application-*.yml`

## Run / Verify

```bash
./gradlew :image-back-server:bootRun
./gradlew :image-back-server:compileJava
./gradlew :image-back-server:test
```

저장 경로나 최종화 규칙을 바꾸면 기존 이미지 URL, 파생 이미지, 첨부 내부 토큰, 미등록 표식, SEMO 조정 작업을 함께 검증합니다.
