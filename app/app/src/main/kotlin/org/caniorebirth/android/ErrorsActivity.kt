package org.caniorebirth.android

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.caniorebirth.zev.CanBusError
import org.caniorebirth.zev.ZevCanParser

class ErrorsActivity : AppCompatActivity() {

    private val parser = ZevCanParser()
    private lateinit var adapter: ErrorAdapter
    private lateinit var rvErrors: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_errors)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "CAN Bus Errors"

        rvErrors = findViewById(R.id.rvErrors)
        rvErrors.layoutManager = LinearLayoutManager(this)
        adapter = ErrorAdapter(parser) { err ->
            parser.clearError(err.code)
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Error ${err.code} cleared", Toast.LENGTH_SHORT).show()
        }
        rvErrors.adapter = adapter
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_errors, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_clear_all) {
            parser.clearAllErrors()
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "All errors cleared", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private inner class ErrorAdapter(
        private val parser: ZevCanParser,
        private val onClear: (CanBusError) -> Unit
    ) : RecyclerView.Adapter<ErrorViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ErrorViewHolder =
            ErrorViewHolder(android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_error, parent, false))

        override fun onBindViewHolder(holder: ErrorViewHolder, position: Int) {
            val err = parser.getCurrentErrors()[position]
            holder.bind(err)
        }

        override fun getItemCount(): Int = parser.getCurrentErrors().size
    }

    inner class ErrorViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        private val tvCode = view.findViewById<android.widget.TextView>(R.id.tvErrorCode)
        private val tvDesc = view.findViewById<android.widget.TextView>(R.id.tvErrorDesc)
        private val btnClear = view.findViewById<android.widget.Button>(R.id.btnClearError)

        fun bind(err: org.caniorebirth.zev.CanBusError) {
            tvCode.text = "0x${err.code.toString(16).uppercase()}"
            tvDesc.text = "${err.description} ${if (err.cleared) "[CLEARED]" else ""}"
            btnClear.setOnClickListener {
                parser.clearError(err.code)
                Toast.makeText(itemView.context, "Cleared 0x${err.code.toString(16)}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
