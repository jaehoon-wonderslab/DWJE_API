# MES 데이터 조회 가이드

덕우전자 AX 시스템에서 `mes` 스키마를 조회하는 쿼리를 작성·개선할 때 참고한다.
**사람과 AI 모두 이 문서를 먼저 읽고 쿼리를 쓴다.**

여기 적힌 수치는 전부 실제 DB(`localhost:5432 / dwjedb`)에서 확인한 값이다. 추정치가 아니다.
확인 기준일은 2026-09-01, 실적 데이터 보유 구간은 **2020-01-02 ~ 2026-08-30** 이다.

---

## 0. 절대 규칙

> **`mes` 스키마는 조회 전용이다.**
> 구조 변경·데이터 변경/삭제/갱신을 하지 않는다. `SELECT` 만 한다.
> 파생 결과를 저장해야 하면 `ax` 스키마에 쓴다.
> 이 규칙은 `MesReadOnlyContractTest` 가 빌드할 때 Kotlin 소스와 SQL 스크립트를 모두 검사한다.
> (검사 대상 구문: `INSERT INTO` / `UPDATE` / `DELETE FROM` / `TRUNCATE` / `ALTER TABLE` /
> `DROP TABLE` / `CREATE TABLE` / `CREATE INDEX` / `ALTER SCHEMA` / `DROP SCHEMA` / `GRANT` / `REVOKE`)

---

## 1. 핵심 테이블 지도

| 테이블 | 행 수 | 역할 | PK |
|---|---:|---|---|
| `mes.tb_pop_label_hist` | 7,493,335 | **생산 실적의 원장.** 양품/불량 *수량* 의 기준 | `(plant_cd, wc_cd, lot_no, serial_no)` |
| `mes.tb_pop_defect_hist` | 8,999,912 | 불량 *유형별* 상세 | `(plant_cd, wc_cd, lot_no, serial_no, defect_cd)` |
| `mes.tb_pop_stock_hist` | 24,409,697 | 재고 이동 이력 | — |
| `mes.tb_md_item` | 2,006 | 품목(=생산 모델) 마스터 | `(plant_cd, item_cd)` |
| `mes.tb_md_eqpt` | 1,540 | 설비 마스터 | `(plant_cd, eqpt_cd)` |
| `mes.tb_md_eqpt_by_workcenter` | 1,334 | 설비 ↔ 공정 매핑 | `(plant_cd, wc_cd, eqpt_cd)` |
| `mes.tb_md_workcenter` | 39 | 공정(작업장) 마스터 | — |
| `mes.tb_md_defect` | 172 | 불량 코드 마스터 | — |
| `mes.tb_md_mold` / `tb_md_mold_by_eqpt` | 683 / 881 | 금형 마스터·설비 매핑 | — |

집계의 출발점은 거의 항상 `tb_pop_label_hist` 다.

---

## 2. 함정 목록

실제로 틀린 결과를 만들었던 것들이다. 각 항목은 **증상 → 원인 → 올바른 방법** 순이다.

### 2-1. "N공장" 을 `plant_cd` 로 거르면 결과가 0건이거나 전건이다 ★

**증상** — `WHERE plant_cd = 'PL02'` 로 2공장을 거르면 0건. `'PL01'` 로 걸면 전 공장이 다 나온다.

**원인** — `mes` 의 `plant_cd` 는 **전건 `PL01` 하나뿐**이다. 공장 구분은 워크센터 *이름* 에 들어 있다.

```
W110  A-프레스 작업장(M-1공장)      ← 1공장 프레스 (설비 34대)
W150  B-프레스 작업장(M-2공장)      ← 2공장 프레스 (설비 26대)
W120  C-프레스 작업장(M-3공장)      ← 3공장 프레스 (설비 34대)
S137  A-PACKING(M-1공장)
S131  C1-PACKING(M-3공장)
S141  J프레스 작업장(M-1공장)       ← valid_to_dt 2023-03-13, 이미 폐지
```

**올바른 방법** — `wc_nm` 으로 걸고 **유효기간까지 확인**한다. 폐지된 작업장이 섞이면 안 된다.

```sql
SELECT w.plant_cd, w.wc_cd, w.wc_nm
FROM mes.tb_md_workcenter w
WHERE w.plant_cd = :plantCd
  AND w.wc_nm LIKE '%프레스%'
  AND w.wc_nm LIKE '%M-1공장%'
  AND now() BETWEEN w.valid_from_dt AND w.valid_to_dt
```

`wc_cd` 를 알고 있으면 `w.wc_cd = 'W110'` 로 직접 거는 편이 안전하고 빠르다.

### 2-2. 불량 이력을 `lot_no` 로만 조인하면 전 설비에 같은 값이 붙는다 ★★★

**증상** — 설비별 주요 불량 유형을 뽑았더니 28대 전부가 `기타 305160` 으로 **동일**했다.

**원인** — 이 데이터의 `lot_no` 는 **날짜 문자열**이다. `20260828` 하나에 설비 28대 · 품목 6종이 들어 있다.
게다가 `tb_pop_defect_hist` 에는 **`eqpt_cd` 컬럼이 없다.** LOT 으로만 조인하면 그날 그 공정의
모든 불량이 모든 설비에 붙는다.

**올바른 방법** — 라벨 이력의 PK 전체, 즉 **`(plant_cd, wc_cd, lot_no, serial_no)`** 로 조인해
`serial_no` 를 통해 설비를 특정한다. `tb_pop_defect_hist` 의 PK 앞 4개 컬럼과 정확히 일치하므로
인덱스도 그대로 탄다.

```sql
INNER JOIN mes.tb_pop_defect_hist dh
        ON dh.plant_cd  = lh.plant_cd
       AND dh.wc_cd     = lh.wc_cd
       AND dh.lot_no    = lh.lot_no
       AND dh.serial_no = lh.serial_no
```

**검증** — W110 / 2026-08-28 기준 매칭률 100% (331행 전건), 설비별
`sum(defect_hist.qty)` 와 `sum(label_hist.defect)` 차이 **0**.

### 2-3. 불량 이력을 날짜로 따로 필터링하면 분자·분모가 어긋난다 ★★

**증상** — 불량률이 실제보다 크게 나온다.

**원인** — `label_hist` 와 `defect_hist` 에 같은 날짜 범위를 **각각 독립적으로** 걸면 서로 다른 행 집합이 잡힌다.
2026-08-28 / PL01 전체 실측:

| 산출 방식 | 불량 수량 |
|---|---:|
| `label_hist.defect` 합 (기준) | 427,562 |
| `defect_hist.qty` 를 **날짜로 필터** | 792,473 (기준의 1.85배) |
| `defect_hist.qty` 를 **serial 조인** | 781,108 |

**올바른 방법** — 기간 필터는 **`label_hist.ins_date` 에만** 걸고, `defect_hist` 는 위 2-2 방식으로 조인해서 끌어온다.
불량 유형별 실적 조회에서 `defect_hist` 에 직접 날짜를 걸지 않는다.

### 2-4. 수량 합계는 `label_hist` 기준, 유형 구성은 생산 불량 코드만 ★★★

2-2 방식으로 정확히 조인해도 **두 테이블의 합계가 공정에 따라 어긋난다.** 2026-08-28 실측:

| 공정 | `label.defect` | `defect_hist.qty` | 차이 |
|---|---:|---:|---:|
| S134 G-Cosmetic(FQC) | 92,201 | 242,201 | **+150,000** |
| S138 G-PACKING | 47,183 | 159,743 | **+112,560** |
| S137 A-PACKING(M-1공장) | 21,154 | 73,930 | +52,776 |
| W160 C2-PACKING(M-3공장) | 8,555 | 41,195 | +32,640 |
| S131 C1-PACKING(M-3공장) | 24,347 | 30,432 | +6,085 |
| W110 A-프레스(M-1공장) | 37,030 | 36,755 | −275 |
| W150 B-프레스(M-2공장) | 24,520 | 24,280 | −240 |
| 그 외 14개 공정 | — | — | **0 (정확히 일치)** |

#### 원인 — `(R)` / `T` 코드는 생산 불량이 아니다

차이는 전부 **불량코드 이름에 `(R)` 이 붙은 12종과 `T`(DF142) 1종**에서 나온다.
이 코드가 붙은 라벨은 `label.defect` 가 0(양품 계상)이고, `dh.qty` 에는 **그 라벨의 전체 수량** 이 들어간다.
DF158 `기타 (R)` 의 `remark` 는 `실수량안맞음` 이고 `qty` 가 `label.normal` 과 같다.
재작업·반품 처리 표시에 가깝고 생산 불량 수량이 아니다.

2026-08 한 달 실측 — 갈림이 완벽하다.

| 구분 | 행수 | `label.defect = 0` 인 행 | 비율 | 이력 수량 |
|---|---:|---:|---:|---:|
| 일반 코드 | 97,035 | **0** | 0.0% | 10,099,448 |
| `(R)` / `T` 코드 | 18,823 | **18,823** | **100.0%** | 7,887,358 |

`(R)`/`T` 를 빼고 보면 라벨 15만 9,136건 중 불일치는 907건(0.57%), 수량 차이는 1.0% 다.

#### 규칙

- **불량 *수량* · 불량률** → `label_hist.defect`. 이게 원장이다. (R)/T 는 자동으로 빠진다.
- **불량 *유형* 구성** → `defect_hist` 를 쓰되 **(R)/T 코드를 제외** 한다.
  빼지 않으면 유형 1위가 `기타 (R)` 로 나와 불량률과 앞뒤가 맞지 않는다.
  ```
  수정 전 : 기타 (R) > 얼룩 > T > 치수 (R) > 찍힘
  수정 후 : 얼룩 > 찍힘 > 스크래치 > 품질검사 > 릴검사불량
  ```
- 두 테이블의 수량을 **하나의 계산식에서 섞지 않는다.**
  (예: 분자 `defect_hist.qty`, 분모 `label_hist` 총생산 → 금지. 2-3 참고)

#### 제외 목록은 `ax` 기준정보로 관리한다

`mes` 는 조회 전용이고 `tb_md_defect` 에 구분 컬럼도 없어 원본에 표시할 수 없다.
그래서 `ax.tb_sys_code` 의 `QC_DEFECT_NONPROD` 그룹에 13건을 등록해 두었다(V10 마이그레이션).
코드 수정 없이 전산팀이 조정할 수 있고, 집계에 포함하려면 `use_flg` 를 `'N'` 으로 바꾸면 된다.

쿼리에는 `DefectSql.excludeNonProduction("dh")` 로 붙인다.

```sql
AND NOT EXISTS (SELECT 1 FROM ax.tb_sys_code nc
                 WHERE nc.group_cd = 'QC_DEFECT_NONPROD' AND nc.use_flg = 'Y'
                   AND nc.code = dh.defect_cd)
```

#### 유형을 지정해 수량을 볼 때는 안분한다

특정 불량 유형의 수량이 필요하면, 라벨의 불량 수량을 그 라벨의 유형 구성비로 나눈다.

```
해당 유형 수량 = label.defect × (해당 유형 qty ÷ 그 라벨의 전체 유형 qty 합)
```

유형별 수량의 합이 라벨 총 불량과 어긋나지 않게 하는 방식이며,
두 테이블 수량이 이미 맞는 공정에서는 `defect_hist.qty` 와 같은 값이 된다.
`QualityRepository.findDefectSummary` 가 이 방식으로 구현되어 있다.

#### 안분해도 남는 물량이 있다 — '유형 미상' 으로 명시한다

안분은 **유형이 하나라도 붙은 라벨** 안에서만 수량을 나눈다.
라벨에 불량 수량은 있는데 생산 불량코드 이력이 **한 건도 없는** 라벨은 어느 유형에도 들어가지 않는다.

2026-08-01 ~ 08-30 / PL01 실측:

| 구분 | 라벨 | 수량 |
|---|---:|---:|
| `defect > 0` 인 라벨 (원장 총량) | 48,821 | 10,203,189 |
| 생산 불량코드 이력이 있는 라벨 | 47,914 | 10,099,448 |
| **생산 불량코드 이력이 0건인 라벨** | **907** | **103,741 (1.02%)** |

이 907건은 2-4 표의 "불일치 907건" 과 같은 집합이다.
그래서 `Σ(유형별 수량) = 원장 총량` 은 안분만으로는 성립하지 않는다.

같은 기간 안분의 효과를 재보면 이렇다. 유형이 붙은 라벨 47,914건 중
`Σdh.qty ≠ label.defect` 인 라벨은 **0건** 이었다.
(R)/T 13종을 걷어내면 8월엔 라벨마다 이미 값이 맞아, 안분식이 라벨별로 ×1 이 된다.
즉 **안분이 적용되었는데 결과가 안 바뀌는 달이 있다** — 2-4 마지막 줄이 예고한 대로다.
값이 안 바뀌는 것을 "안분이 빠졌다" 로 오진하지 않도록, 판정은 라벨 단위 대조로 한다.

#### 규칙

- 유형 구성 응답에는 차액을 **`유형 미상` 행/세그먼트로 명시** 한다.
  그래야 응답만 보고 `부분의 합 = 전체` 가 성립하고, 엑셀·외부 소비자도 1% 의 정체를 안다.
- 차액은 **표시값(반올림 후) 기준** 으로 잡는다. 그러면 `Σcnt` 가 원장 총량과 정확히 맞는다.
- 코드 자리는 비운다(`defectCd`/`code` = null). 실제 불량코드가 아니라서 화면이 구분해 그릴 수 있다.
- 비중(`ratio`) 합은 반올림 때문에 100.00 에서 소폭 벗어날 수 있다(43행 기준 실측 100.02).
  검증은 허용 오차를 두고 한다. 유형 미상의 `ratio` 를 억지로 맞추면 그 행의 `cnt` 와 어긋난다.

구현은 `DefectSql.UNTYPED_LABEL` · `withUntypedSegment()` 와
`QualityRepository.findDefectByType` 에 있다. 적용 API 4건:

```
quality/defects/by-type
dashboard/kpi/defect-distribution
dashboard/ai/defect-composition
dashboard/process/defect-composition
```

### 2-5. `defect` 는 NULL 이 많다 — `coalesce` 없이 집계·정렬하면 틀린다 ★★

**증상** — `ORDER BY sum(lh.defect) DESC` 했더니 상위가 전부 0 으로 나왔다.

**원인** — `label_hist.defect` 는 nullable 이고 **표본의 약 61% 가 NULL** 이다.
PostgreSQL 은 `DESC` 정렬에서 NULL 을 **먼저** 놓는다(`NULLS FIRST` 가 기본).

2% 표본(150,744행) 기준 NULL 비율:

| 컬럼 | NULL 행 | 비율 |
|---|---:|---:|
| `defect` | 92,521 | 약 61% |
| `mold_cd` | 130,867 | 약 87% |
| `normal` | 4,730 | 약 3% |
| `eqpt_cd` | 4,731 | 약 3% |

**올바른 방법** — 집계·정렬 양쪽에 `coalesce` 를 건다.

```sql
sum(coalesce(lh.defect, 0))
ORDER BY sum(coalesce(lh.defect, 0)) DESC
```

설비 단위로 집계할 때는 `eqpt_cd IS NOT NULL` 조건도 함께 건다(약 3%가 설비 미기재).

### 2-6. `del_flg = 'N'` 을 빼먹으면 삭제분이 섞인다 ★

표본 기준 `del_flg = 'Y'` 가 약 3%(4,491행 / 150,633행)다.
`tb_pop_label_hist` 를 읽을 때는 **항상** `AND lh.del_flg = 'N'` 을 건다.
성능상으로도 이 조건이 있어야 부분 인덱스 `ix_pop_label_live` 를 탄다.

### 2-7. `tb_md_eqpt.model_nm` 은 생산 모델이 아니다 ★

설비 *기종*(제조사 모델명)이다. 컬럼 순서상 `serial_no` · `manufacturer` 옆에 있다.
게다가 **1,540대 중 30대만 채워져 있다.**

생산 모델은 `tb_pop_label_hist.item_cd` → `tb_md_item.item_nm` 이다.
모델 코드만 필요하면 `split_part(item_cd, '-', 1)` (품목코드는 `모델 + 공정접미사` 구조 —
`D34BS-F`, `D34BS-P2` → `D34BS`).

### 2-8. 기준일 기본값을 `오늘` 로 두면 전부 0 이 된다 ★★

실적은 **2026-08-30** 까지만 있다. 운영에서도 당일 실적이 올라오기 전에는 같은 상황이 된다.
화면·API 기본값을 `CURRENT_DATE` 로 두면 대시보드가 통째로 비어 보인다.

`GET /api/v1/common/data-range` 가 실적 보유 구간(`fromDate` / `toDate`)을 돌려주므로
기준일 기본값은 `toDate` 로 잡는다.

### 2-9. `min/max` 전체 집계는 풀스캔이다 — LATERAL 로 인덱스를 태운다 ★

`tb_pop_label_hist` 에 `ins_date` 단독 B-tree 인덱스가 없다(BRIN 만 있다).
단순 `min(ins_date)/max(ins_date)` 는 749만행 풀스캔으로 **991ms** 가 걸린다.

워크센터(39건) 단위로 쪼개 `ix_pop_label_live(plant_cd, wc_cd, ins_date)` 의 양 끝
한 건씩만 읽게 하면 **22ms** 로 떨어진다.

```sql
SELECT min(b.min_dt)::date, max(b.max_dt)::date
FROM mes.tb_md_workcenter w
CROSS JOIN LATERAL (
    SELECT (SELECT l.ins_date FROM mes.tb_pop_label_hist l
             WHERE l.plant_cd=w.plant_cd AND l.wc_cd=w.wc_cd AND l.del_flg='N'
             ORDER BY l.ins_date ASC LIMIT 1)  AS min_dt,
           (SELECT l.ins_date FROM mes.tb_pop_label_hist l
             WHERE l.plant_cd=w.plant_cd AND l.wc_cd=w.wc_cd AND l.del_flg='N'
             ORDER BY l.ins_date DESC LIMIT 1) AS max_dt
) b
WHERE w.plant_cd = :plantCd
```

### 2-10. 상관 서브쿼리에서 "subquery uses ungrouped column" 이 난다 ★

`GROUP BY` 밖의 컬럼(예: `lh.ins_date`)을 서브쿼리에서 참조하면 나는 오류다.
서브쿼리 중첩 대신 **CTE 를 각각 집계한 뒤 조인**하는 형태로 바꾼다.
(`ProductionRepository.baseResultSql` 이 이 방식으로 재작성되어 있다.)

### 2-11. `ax` 제품 마스터는 부트스트랩 파생값이다 ★

`ax.tb_prod_family` / `tb_prod_product` / `tb_prod_item_map` 은 MES 품목코드 규칙에서
자동 파생시킨 값이며 `ins_user = 'BOOTSTRAP'` 으로 표시된다.

- MES 의 `product_family` 는 **2,006건 전건이 비어 있어** 근거로 쓸 수 없다.
- 제품군은 `project_nm` 괄호명에서 뽑았고(`23Y(Vr-Shield-Can)` → `Vr-Shield-Can`),
  괄호 표기가 없는 543건은 `미분류` 로 묶였다. → **`미분류` 가 122개 제품으로 가장 큰 제품군**이다.
- 고객사(`customer_id`)는 MES 에 정보가 없어 **전부 NULL** 이다.

제품군 기준 집계 결과를 해석할 때 이 점을 감안한다. 생성 규칙은
`src/main/resources/db/local/seed_product_master.sql` 머리말에 있다.

---

## 3. 쿼리 작성 체크리스트

`tb_pop_label_hist` 를 읽는 쿼리를 쓰거나 고칠 때 순서대로 확인한다.

- [ ] `del_flg = 'N'` 을 걸었는가 (2-6)
- [ ] 수량 컬럼에 `coalesce(..., 0)` 을 씌웠는가 — 집계와 **`ORDER BY` 양쪽** (2-5)
- [ ] 설비 단위 집계라면 `eqpt_cd IS NOT NULL` 을 걸었는가 (2-5)
- [ ] 공장 구분을 `plant_cd` 가 아니라 `wc_nm`/`wc_cd` 로 했는가 (2-1)
- [ ] 워크센터 유효기간(`valid_from_dt` / `valid_to_dt`)을 확인했는가 (2-1)
- [ ] 불량 이력 조인에 `serial_no` 까지 넣었는가 (2-2)
- [ ] 기간 필터를 `label_hist` 에만 걸었는가 — `defect_hist` 에 따로 걸지 않았는가 (2-3)
- [ ] 불량 *수량* 은 `label_hist`, *유형 구성* 은 `defect_hist` 로 분리했는가 (2-4)
- [ ] 유형 구성 쿼리에 `DefectSql.excludeNonProduction()` 를 붙였는가 — (R)/T 코드 제외 (2-4)
- [ ] 기준일 기본값이 `오늘` 이 아닌가 (2-8)
- [ ] `mes` 에 쓰기 구문이 없는가 (0장)

---

## 4. 성능 노트

`tb_pop_label_hist` 인덱스

| 인덱스 | 정의 | 언제 타는가 |
|---|---|---|
| `ix_pop_label_live` | `(plant_cd, wc_cd, ins_date) WHERE del_flg='N'` | **주력.** 공정 + 기간 조회 |
| `pk_tb_pop_label_hist` | `(plant_cd, wc_cd, lot_no, serial_no)` | 불량 이력 조인 |
| `ix_pop_label_item` | `(plant_cd, item_cd)` | 품목 단위 조회 |
| `ix_pop_label_eqpt` | `(plant_cd, eqpt_cd) WHERE eqpt_cd IS NOT NULL` | 설비 단위 조회 |
| `ix_pop_label_mold` | `(plant_cd, mold_cd) WHERE mold_cd IS NOT NULL` | 금형 단위 조회 |
| `bx_pop_label_ins` | BRIN `(ins_date)` | 광범위 기간 스캔 |

`tb_pop_defect_hist` PK 는 `(plant_cd, wc_cd, lot_no, serial_no, defect_cd)` 이므로
2-2 의 조인 조건이 그대로 인덱스 선두 4컬럼과 맞는다.

실측 소요 시간 (`docs/queries/press_line_status.sql` 기준)

| 범위 | 소요 |
|---|---:|
| 1일 | 약 8ms |
| 7일 | 약 30ms |

기간이 길어지면 `label` CTE 가 커지므로, 월 단위 이상은 일자별 사전 집계를 검토한다.

---

## 5. 참고 쿼리

| 파일 | 내용 |
|---|---|
| `docs/queries/press_line_status.sql` | 1공장 프레스 설비별 · 생산모델별 실적/불량 현황. 2-1 · 2-2 · 2-4 · 2-5 · 2-6 · 2-7 이 모두 적용된 표준 예시 |

Repository 구현 예시

| 파일 | 참고할 점 |
|---|---|
| `repository/ProductionRepository.kt` | CTE 분해로 ungrouped column 회피 (2-10) |
| `repository/QualityRepository.kt` | LATERAL 로 대표 불량 유형 추출, `coalesce` 정렬 (2-5) |
| `repository/CommonMasterRepository.kt` | `findProductionDateRange` — LATERAL 인덱스 스캔 (2-9) |
| `repository/DashboardProcessRepository.kt` | `ax.tb_prod_item_map` 경유 품목→제품 변환 (2-11) |
| `common/util/DefectSql.kt` | 생산 불량 미계상 코드 제외 조건. 유형 구성 쿼리 16곳에서 사용 (2-4) |
| `repository/QualityRepository.kt` `findDefectSummary` | 라벨 기준 불량률 + 유형 안분 (2-4) |

---

## 6. 검증 스니펫

쿼리를 고친 뒤 결과가 맞는지 확인할 때 쓴다.

**불량 수량이 원장과 맞는가**

```sql
-- 두 값의 차이가 0 이어야 한다 (검사·포장 공정은 2-4 참고, 차이가 정상)
SELECT lh.wc_cd,
       sum(coalesce(lh.defect,0))              AS label_ng,
       coalesce(sum(dh.qty), 0)                AS defect_ng,
       -- 부호 규약은 2-4 표와 동일하게 (불량이력 − 라벨) 로 맞춘다
       coalesce(sum(dh.qty),0) - sum(coalesce(lh.defect,0)) AS diff
FROM mes.tb_pop_label_hist lh
LEFT JOIN LATERAL (
    SELECT sum(d.qty) AS qty FROM mes.tb_pop_defect_hist d
     WHERE d.plant_cd=lh.plant_cd AND d.wc_cd=lh.wc_cd
       AND d.lot_no=lh.lot_no AND d.serial_no=lh.serial_no
) dh ON TRUE
WHERE lh.plant_cd = :plantCd AND lh.del_flg = 'N'
  AND lh.ins_date >= :fromTs AND lh.ins_date < :toTs
GROUP BY 1 ORDER BY abs(coalesce(sum(dh.qty),0) - sum(coalesce(lh.defect,0))) DESC;
```

**대상 공정이 맞게 잡혔는가** (psql 로 직접 실행할 때는 `-c` 가 아니라 `-f` 로 돌려야 `:plantCd` 가 치환된다)

```sql
SELECT wc_cd, wc_nm, valid_from_dt::date, valid_to_dt::date,
       now() BETWEEN valid_from_dt AND valid_to_dt AS 유효
FROM mes.tb_md_workcenter WHERE plant_cd = :plantCd ORDER BY wc_cd;
```

**실적 보유 구간 확인** — 2-9 의 LATERAL 쿼리를 쓰거나 `GET /api/v1/common/data-range` 를 호출한다.
