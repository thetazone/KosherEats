package com.greeneats.seller.ui.screens.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.greeneats.seller.data.models.KosherCertification
import com.greeneats.seller.ui.theme.BackgroundBlack
import com.greeneats.seller.ui.theme.DividerColor
import com.greeneats.seller.ui.theme.ErrorRed
import com.greeneats.seller.ui.theme.Orange
import com.greeneats.seller.ui.theme.SurfaceDark
import com.greeneats.seller.ui.theme.SurfaceDarkElevated
import com.greeneats.seller.ui.theme.TextMuted
import com.greeneats.seller.ui.theme.TextTertiary
import com.greeneats.seller.ui.theme.TextWhite
import com.greeneats.seller.ui.viewmodels.CreateRestaurantViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRestaurantScreen(
    onCreated: () -> Unit,
    viewModel: CreateRestaurantViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val certificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            viewModel.setUploadingCertificate(true)
            viewModel.setCertificateError(null)
            val url = uploadCertificate(context, uri, viewModel)
            if (url != null) {
                viewModel.updateCertificateUrl(url)
            } else {
                viewModel.setCertificateError("Upload failed. Please try again.")
            }
            viewModel.setUploadingCertificate(false)
        }
    }

    LaunchedEffect(state.createdRestaurant) {
        if (state.createdRestaurant != null) {
            onCreated()
        }
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextWhite,
        unfocusedTextColor = TextWhite,
        focusedBorderColor = Orange,
        unfocusedBorderColor = DividerColor,
        cursorColor = Orange,
        focusedLabelColor = Orange,
        unfocusedLabelColor = TextMuted,
        focusedContainerColor = SurfaceDark,
        unfocusedContainerColor = SurfaceDark,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        TopAppBar(
            title = {
                Text(
                    "Set Up Your Restaurant",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ---- Section 1: Basics ----
            SectionHeader(icon = Icons.Filled.Restaurant, title = "Basics")
            SectionCard {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::updateName,
                    label = { Text("Restaurant Name *") },
                    placeholder = { Text("e.g., Jerusalem Grill", color = TextMuted) },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.description,
                    onValueChange = viewModel::updateDescription,
                    label = { Text("Short Description") },
                    placeholder = { Text("Tell customers about your restaurant...", color = TextMuted) },
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    minLines = 3,
                    maxLines = 5,
                    supportingText = {
                        Text(
                            "${state.description.length}/2000",
                            color = TextTertiary,
                            fontSize = 12.sp,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- Section 2: Contact ----
            SectionHeader(icon = Icons.Filled.Phone, title = "Contact")
            SectionCard {
                OutlinedTextField(
                    value = state.phone,
                    onValueChange = viewModel::updatePhone,
                    label = { Text("Phone *") },
                    placeholder = { Text("(555) 123-4567", color = TextMuted) },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::updateEmail,
                    label = { Text("Email *") },
                    placeholder = { Text("contact@restaurant.com", color = TextMuted) },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- Section 3: Address ----
            SectionHeader(icon = Icons.Filled.LocationOn, title = "Address")
            SectionCard {
                OutlinedTextField(
                    value = state.street,
                    onValueChange = viewModel::updateStreet,
                    label = { Text("Street *") },
                    placeholder = { Text("123 Main St", color = TextMuted) },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.city,
                    onValueChange = viewModel::updateCity,
                    label = { Text("City *") },
                    placeholder = { Text("Brooklyn", color = TextMuted) },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.state,
                        onValueChange = viewModel::updateState,
                        label = { Text("State *") },
                        placeholder = { Text("NY", color = TextMuted) },
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                        ),
                        modifier = Modifier.weight(0.4f),
                    )

                    OutlinedTextField(
                        value = state.zipCode,
                        onValueChange = viewModel::updateZipCode,
                        label = { Text("ZIP Code *") },
                        placeholder = { Text("11230", color = TextMuted) },
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.6f),
                    )
                }
            }

            // ---- Section 4: Kosher Certification ----
            SectionHeader(icon = Icons.Filled.VerifiedUser, title = "Kosher Certification")
            SectionCard {
                Text(
                    text = "Certification Type *",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Horizontal scrollable pill/chip selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KosherCertification.entries.forEach { cert ->
                        val selected = state.kosherCertification == cert
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    if (selected) Orange.copy(alpha = 0.15f)
                                    else SurfaceDarkElevated,
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) Orange else SurfaceDarkElevated,
                                    shape = RoundedCornerShape(18.dp),
                                )
                                .clickable { viewModel.updateKosherCertification(cert) }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = cert.displayName,
                                color = if (selected) Orange else TextMuted,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.certifyingAgency,
                    onValueChange = viewModel::updateCertifyingAgency,
                    label = { Text("Certifying Agency") },
                    placeholder = { Text("e.g., Rabbi Cohen", color = TextMuted) },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Kosher Certificate Photo *",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (state.certificateUrl.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        AsyncImage(
                            model = state.certificateUrl,
                            contentDescription = "Kosher certificate",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        IconButton(
                            onClick = { viewModel.updateCertificateUrl("") },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(BackgroundBlack.copy(alpha = 0.6f)),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove",
                                tint = TextWhite,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Orange.copy(alpha = 0.9f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = TextWhite,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Uploaded",
                                color = TextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceDarkElevated)
                            .border(
                                width = 1.dp,
                                color = if (state.certificateError != null) ErrorRed else DividerColor,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable(enabled = !state.isUploadingCertificate) {
                                certificatePicker.launch("image/*")
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isUploadingCertificate) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = Orange,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Uploading...",
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                )
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.UploadFile,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(32.dp),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Tap to upload certificate photo",
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }

                if (state.certificateError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.certificateError!!,
                        color = ErrorRed,
                        fontSize = 12.sp,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Cholov Yisroel",
                    checked = state.isCholovYisroel,
                    onToggle = viewModel::toggleCholovYisroel,
                )

                ToggleRow(
                    label = "Pas Yisroel",
                    checked = state.isPasYisroel,
                    onToggle = viewModel::togglePasYisroel,
                )

                ToggleRow(
                    label = "Glatt Kosher",
                    checked = state.isGlattKosher,
                    onToggle = viewModel::toggleGlattKosher,
                )
            }

            // ---- Section 5: Cuisine ----
            SectionHeader(icon = Icons.Filled.Fastfood, title = "Cuisine")
            SectionCard {
                OutlinedTextField(
                    value = state.cuisineTags,
                    onValueChange = viewModel::updateCuisineTags,
                    label = { Text("Cuisine Tags") },
                    placeholder = { Text("Israeli, Grill, Sushi (comma-separated)", color = TextMuted) },
                    singleLine = true,
                    colors = textFieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- Error ----
            if (state.error != null) {
                Text(
                    text = state.error!!,
                    color = ErrorRed,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // ---- Submit Button ----
            Button(
                onClick = viewModel::submit,
                enabled = state.isFormValid && !state.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Orange,
                    contentColor = TextWhite,
                    disabledContainerColor = Orange.copy(alpha = 0.3f),
                    disabledContentColor = TextWhite.copy(alpha = 0.6f),
                ),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        color = TextWhite,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = "Create Restaurant",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ---- Reusable sub-components ----

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Orange,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = title,
            color = TextWhite,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(16.dp),
    ) {
        content()
    }
}

private suspend fun uploadCertificate(
    context: android.content.Context,
    uri: Uri,
    viewModel: CreateRestaurantViewModel,
): String? = withContext(Dispatchers.IO) {
    try {
        val contentType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val presignResponse = viewModel.presignUpload("restaurant/certificate", contentType)
            ?: return@withContext null

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(presignResponse.uploadUrl)
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        response.use { if (it.isSuccessful) presignResponse.publicUrl else null }
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextWhite,
            fontSize = 14.sp,
        )
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextWhite,
                checkedTrackColor = Orange,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = SurfaceDarkElevated,
                uncheckedBorderColor = SurfaceDarkElevated,
            ),
        )
    }
}
