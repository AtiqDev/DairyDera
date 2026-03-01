package com.example.dairypos.data.repository.accounting

import android.content.ContentValues
import com.example.dairypos.DatabaseHelper
import org.json.JSONArray
import org.json.JSONObject

class OperationalEntitiesRepository(private val helper: DatabaseHelper) {
    private val db get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    /** All modules with their assigned entity count — for Panel A. */
    fun getModulesWithEntityCount(): String {
        val sql = """
            SELECT m.id, m.name,
                   COUNT(oe.id) AS entityCount
              FROM modulesRegistry m
              LEFT JOIN ${DatabaseHelper.T_OPERATIONAL_ENTITIES} oe ON oe.moduleId = m.id
             GROUP BY m.id, m.name
             ORDER BY m.name
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    /** Entities assigned to a specific module — for Panel B. */
    fun getEntitiesByModule(moduleId: Int): String {
        val sql = """
            SELECT id, EntityName, EntityType, TableName, MonetaryColumnName
              FROM ${DatabaseHelper.T_OPERATIONAL_ENTITIES}
             WHERE moduleId = ?
             ORDER BY EntityName
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(moduleId.toString()))).toString()
    }

    /** Entities with no module assigned. */
    fun getUnassignedEntities(): String {
        val sql = """
            SELECT id, EntityName, EntityType, TableName, MonetaryColumnName
              FROM ${DatabaseHelper.T_OPERATIONAL_ENTITIES}
             WHERE moduleId IS NULL
             ORDER BY EntityName
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    /** Single entity detail — for Panel C. */
    fun getEntityDetail(entityId: Int): String {
        val sql = """
            SELECT oe.id, oe.EntityName, oe.EntityType, oe.TableName, oe.MonetaryColumnName,
                   oe.moduleId, m.name AS moduleName
              FROM ${DatabaseHelper.T_OPERATIONAL_ENTITIES} oe
              LEFT JOIN modulesRegistry m ON oe.moduleId = m.id
             WHERE oe.id = ?
        """.trimIndent()
        val arr = helper.fetchAll(rdb.rawQuery(sql, arrayOf(entityId.toString())))
        return if (arr.length() > 0) arr.getJSONObject(0).toString()
        else JSONObject().put("error", "Entity not found").toString()
    }

    /**
     * Assign entity to a module.
     * Enforces single-module constraint: returns error if entity already belongs to another module.
     */
    fun assignEntityToModule(entityId: Int, moduleId: Int): String {
        val currentModuleId = helper.fetchScalarInt(
            "SELECT COALESCE(moduleId, 0) FROM ${DatabaseHelper.T_OPERATIONAL_ENTITIES} WHERE id = ?",
            arrayOf(entityId.toString())
        )
        if (currentModuleId != 0 && currentModuleId != moduleId) {
            val currentModuleName = helper.fetchScalarString(
                "SELECT name FROM modulesRegistry WHERE id = ?",
                arrayOf(currentModuleId.toString())
            ) ?: "another module"
            return JSONObject()
                .put("error", "Already assigned to '$currentModuleName'. Remove it first.")
                .toString()
        }
        val rows = db.update(
            DatabaseHelper.T_OPERATIONAL_ENTITIES,
            ContentValues().apply { put("moduleId", moduleId) },
            "id = ?", arrayOf(entityId.toString())
        )
        return JSONObject().put("updated", rows).toString()
    }

    /** Remove entity from its current module (sets moduleId to NULL). */
    fun removeEntityFromModule(entityId: Int): String {
        val rows = db.update(
            DatabaseHelper.T_OPERATIONAL_ENTITIES,
            ContentValues().apply { putNull("moduleId") },
            "id = ?", arrayOf(entityId.toString())
        )
        return JSONObject().put("updated", rows).toString()
    }
}
