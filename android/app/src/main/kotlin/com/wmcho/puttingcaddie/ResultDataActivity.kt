package com.wmcho.puttingcaddie

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 임시: 측정 결과 데이터를 별도 페이지에 표시.
 * debug/test 모드에서 "결과data" 버튼으로 진입.
 */
class ResultDataActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_data)
        val txt = findViewById<TextView>(R.id.txt_result_data)
        val btnClose = findViewById<Button>(R.id.btn_close)
        val data = intent.getStringExtra(EXTRA_RESULT_DATA) ?: "(데이터 없음)"
        txt.text = data
        btnClose.setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_RESULT_DATA = "result_data"
    }
}
