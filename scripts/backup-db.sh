#!/usr/bin/env bash
# prod DB 백업: postgres 컨테이너 안에서 pg_dump(custom format)를 떠서 호스트 폴더에 보관한다.
#
# EC2에서 cron으로 돌린다(crontab -e):
#   0 4 * * * /home/ec2-user/Vita-BE/scripts/backup-db.sh >> /home/ec2-user/db-backups/backup.log 2>&1
#
# 환경변수(모두 선택)
#   BACKUP_DIR     백업 폴더(기본 ~/db-backups)
#   KEEP_DAYS      이 일수가 지난 백업은 지운다(기본 7)
#   BACKUP_S3_URI  예: s3://버킷/vita-db — 있으면 같은 디스크 밖(S3)에도 올린다. aws cli와 쓰기 권한이 필요하다
#
# 복원은 `pg_restore --no-owner --no-privileges -d <DB> <파일>`이다.
set -euo pipefail

CONTAINER="${CONTAINER:-vita-postgres-prod}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/db-backups}"
KEEP_DAYS="${KEEP_DAYS:-7}"

mkdir -p "$BACKUP_DIR"
file="$BACKUP_DIR/vita_prod_$(date +%Y%m%d_%H%M%S).dump"
trap 'rm -f "$file.tmp"' EXIT

# 컨테이너의 POSTGRES_USER/POSTGRES_DB 값을 그대로 쓴다.
docker exec "$CONTAINER" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$file.tmp"

# pg_dump가 실패해도 빈 파일이 남을 수 있어 크기를 확인한다.
if [ ! -s "$file.tmp" ]; then
  echo "$(date '+%F %T') 백업 실패: 빈 파일" >&2
  exit 1
fi
mv "$file.tmp" "$file"
echo "$(date '+%F %T') 백업 완료: $file ($(du -h "$file" | cut -f1))"

find "$BACKUP_DIR" -name 'vita_prod_*.dump' -mtime +"$KEEP_DAYS" -delete

if [ -n "${BACKUP_S3_URI:-}" ]; then
  aws s3 cp "$file" "${BACKUP_S3_URI%/}/"
fi
