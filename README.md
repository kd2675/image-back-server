# image-back-server

이미지 업로드와 조회를 담당하는 전용 서버입니다. 업로드 시 날짜 기반 경로에 저장하고, 썸네일 및 여러 크기 파생 이미지를 생성합니다.

## 역할
- 단일 이미지 업로드
- 다중 이미지 일괄 업로드
- 저장된 이미지 조회
- width/height 쿼리 기반 동적 리사이징

## 포트
- 기본 포트: `8081`

## 실행
```bash
./gradlew :image-back-server:bootRun
```

## 빌드 / 테스트
```bash
./gradlew :image-back-server:compileJava
./gradlew :image-back-server:test
```

## 엔드포인트
- `POST /upload`
- `POST /upload/batch`
- `GET /images/{year}/{month}/{day}/{filename}`

## 저장 방식
- 업로드 경로는 `image.upload-dir` 설정을 따릅니다.
- local: `image-back-server/uploads/`
- dev/prod: `/data/image-back-server/uploads/`
- 저장 시 원본과 함께 `_thumb`, `_small`, `_medium`, `_large` 파생 파일을 생성합니다.

## 구성 포인트
- multipart 최대 크기: `100MB`
- OpenAPI UI 의존성 포함
- `width`, `height` 쿼리를 주면 조회 시 동적 리사이징 파일을 생성/재사용합니다.
