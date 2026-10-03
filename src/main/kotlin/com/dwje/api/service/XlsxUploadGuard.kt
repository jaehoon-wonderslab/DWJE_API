package com.dwje.api.service

import com.dwje.api.common.exception.InvalidParameterException

/** 엑셀 업로드 공통 검사 — 업로드 리포트(UPD-03)·용어 사전 업로드가 같이 쓴다 */
object XlsxUploadGuard {

    /**
     * 확장자만 xlsx 로 바꾼 파일과 매크로 통합문서를 거른다 (UPD-03).
     *
     * 파서(WorkbookFactory)는 옛 xls(OLE2)·xlsm 도 열기 때문에 확장자 검사만으로는 막히지 않는다.
     * xlsx 는 ZIP 이므로 ZIP 서명(PK\u0003\u0004)을 보고, 매크로 파트 `xl/vbaProject.bin` 이 있으면 거부한다.
     */
    fun assertPlainXlsx(bytes: ByteArray, original: String) {
        val zipSignature = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        val notXlsx = "xlsx 형식 파일이 아닙니다. 엑셀에서 「Excel 통합 문서(*.xlsx)」 로 저장해 다시 올려 주세요. [$original]"
        if (!zipSignature) throw InvalidParameterException(notXlsx, "file")
        val hasMacro = try {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.any { it.name.trimStart('/').equals("xl/vbaProject.bin", ignoreCase = true) }
            }
        } catch (e: java.util.zip.ZipException) {
            throw InvalidParameterException(notXlsx, "file")
        }
        if (hasMacro) {
            throw InvalidParameterException("매크로가 포함된 통합문서는 올릴 수 없습니다. 매크로 없는 xlsx 로 저장해 다시 올려 주세요. [$original]", "file")
        }
    }
}
