package com.example.uscatterbrain.db.entities;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;

@Dao
public interface IdentityDao {
    @Query("SELECT * FROM identities")
    Maybe<List<KeylessIdentity>> getAll();

    @Transaction
    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    Maybe<List<Identity>> getIdentitiesWithRelations(List<Long> ids);

    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    Maybe<List<KeylessIdentity>> getByID(List<Long> ids);

    @Query("SELECT * FROM identities WHERE givenname IN (:names)")
    Maybe<List<KeylessIdentity>> getByGivenName(String[] names);

    @Query("SELECT * FROM keys WHERE keyID IN (:ids)")
    Maybe<List<Keys>> getKeys(List<Long> ids);

    @Insert
    Single<List<Long>> insertAll(KeylessIdentity... identities);

    @Insert
    Single<List<Long>> insertAll(List<KeylessIdentity> identities);

    @Insert
    Single<List<Long>> insertHashes(List<Hashes> hashes);

    @Insert
    Single<List<Long>> insertKeys(List<Keys> keys);

    @Delete
    Completable delete(KeylessIdentity identity);
}
