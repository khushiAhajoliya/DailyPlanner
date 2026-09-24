package com.dailyplanner.app.data

import com.dailyplanner.app.data.model.Plan
import com.dailyplanner.app.data.model.PlanInput
import com.dailyplanner.app.data.model.Reminder
import com.dailyplanner.app.data.model.ReminderInput
import com.dailyplanner.app.data.model.Template
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface PlannerApi {
    @GET("api/templates") suspend fun templates(): List<Template>
    @GET("api/templates/{id}") suspend fun template(@Path("id") id: String): Template

    @GET("api/plans") suspend fun plans(): List<Plan>
    @GET("api/plans/{id}") suspend fun plan(@Path("id") id: String): Plan
    @POST("api/plans") suspend fun createPlan(@Body body: PlanInput): Plan
    /** Upsert with the phone's own id. */
    @PUT("api/plans/{id}") suspend fun upsertPlan(@Path("id") id: String, @Body body: PlanInput): Plan
    @DELETE("api/plans/{id}") suspend fun deletePlan(@Path("id") id: String)

    @GET("api/reminders") suspend fun reminders(): List<Reminder>
    /** Upsert with the phone's own id. */
    @PUT("api/reminders/{id}") suspend fun putReminder(@Path("id") id: String, @Body body: ReminderInput): Reminder
    @DELETE("api/reminders/{id}") suspend fun deleteReminder(@Path("id") id: String)
}
