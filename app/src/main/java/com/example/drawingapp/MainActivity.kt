package com.example.drawingapp

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.get
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.Exception

class MainActivity : AppCompatActivity() {

    private var drawingView : DrawingView? = null
    private var ibBrush : ImageButton? = null
    private var mImageButtonCurrentPaint: ImageButton? = null
    private var ibGallery : ImageButton? = null
    private var backgroundImage : ImageView? = null
    private var undoButton : ImageButton? = null
    private var saveButton : ImageButton? = null
    private var customProgressDialog : Dialog? = null

    private val openGalleryLauncher : ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()){
           result ->
            if(result.resultCode == RESULT_OK && result.data != null) {
                backgroundImage?.setImageURI(result.data?.data)
            }
        }

    private val requestPermission : ActivityResultLauncher<Array<String>> = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()){
        permissions ->
        permissions.entries.forEach{
            val permissionName = it.key
            val isGranted = it.value
            if(permissionName == Manifest.permission.READ_EXTERNAL_STORAGE) {
                if(isGranted){
                    selectImageFromGallery()
                } else {
                    Toast.makeText(this, "Permission denied for $permissionName", Toast.LENGTH_LONG).show()
                }
            }
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //Initializing
        val linearLayoutPaintColor = findViewById<LinearLayout>(R.id.ll_paint_colors)
        drawingView = findViewById(R.id.drawing_view)
        drawingView?.setSizeForBrush(10.toFloat())
        ibBrush = findViewById(R.id.ib_brush)
        mImageButtonCurrentPaint = linearLayoutPaintColor[1] as ImageButton
        mImageButtonCurrentPaint!!.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.pallet_selected))
        ibGallery = findViewById(R.id.ib_photo_src)
        backgroundImage = findViewById(R.id.iv_background)
        undoButton = findViewById(R.id.ib_undo)
        saveButton = findViewById(R.id.ib_save)

        //Binding
        ibBrush?.setOnClickListener {
            showBrushSizeDialog()
        }
        ibGallery?.setOnClickListener {
            requestStoragePermission()
        }
        undoButton?.setOnClickListener {
            removeLatestPath()
        }
        saveButton?.setOnClickListener {
            saveImage()
        }

        //Bootstrap

    }
    private fun showBrushSizeDialog(){
        val brushDialog = Dialog(this)
        brushDialog.setContentView(R.layout.dialog_brush_size)
        brushDialog.setTitle("Select Brush size")
        val smallBtn : ImageButton? = brushDialog.findViewById(R.id.ib_small_brush)
        smallBtn?.setOnClickListener {
            drawingView?.setSizeForBrush(10.toFloat())
            brushDialog.dismiss()
        }
        val mediumBtn : ImageButton? = brushDialog.findViewById(R.id.ib_medium_brush)
        mediumBtn?.setOnClickListener {
            drawingView?.setSizeForBrush(20.toFloat())
            brushDialog.dismiss()
        }
        val largeBtn : ImageButton? = brushDialog.findViewById(R.id.ib_large_brush)
        largeBtn?.setOnClickListener {
            drawingView?.setSizeForBrush(30.toFloat())
            brushDialog.dismiss()
        }
        brushDialog.show()
    }
    fun paintClicked(view : View){
        if(view !== mImageButtonCurrentPaint){
            val imageButton = view as ImageButton
            val colorTag = imageButton.tag.toString()
            drawingView?.setColor(colorTag)
            imageButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.pallet_selected))
            mImageButtonCurrentPaint?.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.pallet_normal))
            mImageButtonCurrentPaint = view
        }
    }
    private fun requestStoragePermission(){
        if(ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE)){
            showDialog("Application requires storage read/write access",
                "This feature cannot be used because it requires storage read access")
        } else{
            requestPermission.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    private fun showDialog(
        title: String,
        message: String
    ){
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
            .setMessage(message)
            .setPositiveButton("Cancel"){
                dialog, _ -> dialog.dismiss()
            }
        builder.create().show()
    }
    private fun selectImageFromGallery(){
        val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        openGalleryLauncher.launch(pickIntent)
    }
    private fun removeLatestPath(){
        drawingView?.removeLastPath()
    }
    private fun saveImage(){
        if(isReadStorageAllowed()){
            showProgressDialog()
            lifecycleScope.launch {
                val frameLayout : FrameLayout = findViewById(R.id.fl_drawing_view_container)
                val result = saveBitmapFile(getBitmapFromView(frameLayout))
                if(result != "") {
                    shareFile(result)
                }
            }
        }
    }
    private fun getBitmapFromView(view : View) : Bitmap {
        val returnedBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(returnedBitmap)
        val bgDrawable = view.background
        if(bgDrawable != null){
            bgDrawable.draw(canvas)
        } else {
            canvas.drawColor(Color.WHITE)
        }
        view.draw(canvas)
        return returnedBitmap
    }
    private suspend fun saveBitmapFile(mBitmap: Bitmap?): String{
        var result = ""
        withContext(Dispatchers.IO){
            if(mBitmap != null) {
                try {
                    val bytes = ByteArrayOutputStream()
                    mBitmap.compress(Bitmap.CompressFormat.PNG, 90, bytes)
                    val file = File(
                        externalCacheDir?.absoluteFile.toString() +
                                File.separator +
                                "DrawingApp_" +
                                System.currentTimeMillis() / 1000 +
                                ".png"
                    )
                    val fileOutput = FileOutputStream(file)
                    fileOutput.write(bytes.toByteArray())
                    fileOutput.close()
                    result = file.absolutePath
                    runOnUiThread {
                        closeProgressDialog()
                        if(result.isNotEmpty()) {
                            Toast.makeText(this@MainActivity,
                            "File saved successfully :$result",
                            Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity,
                            "Something went wrong while saving the file.",
                            Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e : Exception) {
                    result = ""
                    e.printStackTrace()
                }
            }
        }
        return result
    }
    private fun isReadStorageAllowed() : Boolean {
        val result = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
        return result == PackageManager.PERMISSION_GRANTED
    }
    private fun showProgressDialog() {
        customProgressDialog = Dialog(this@MainActivity)
        customProgressDialog?.setContentView(R.layout.dialog_custom_progress)
        customProgressDialog?.show()
    }
    private fun closeProgressDialog(){
        if (customProgressDialog != null) {
            customProgressDialog?.dismiss()
            customProgressDialog = null
        }
    }
    private fun shareFile(result: String){
        MediaScannerConnection.scanFile(this, arrayOf(result), null){
            path, uri ->
            val shareIntent = Intent()
            shareIntent.action = Intent.ACTION_SEND
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
            shareIntent.type = "image/png"
            startActivity(Intent.createChooser(shareIntent, "Share"))
        }
    }
}