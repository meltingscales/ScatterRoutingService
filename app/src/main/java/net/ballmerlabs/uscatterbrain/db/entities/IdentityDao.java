package net.ballmerlabs.uscatterbrain.db.entities;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore;

import java.util.List;

import javax.inject.Inject;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;

@Dao
public interface IdentityDao {
    @Transaction
    @Query("SELECT * FROM identities")
    Single<List<Identity>> getAll();

    @Transaction
    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    Maybe<List<Identity>> getIdentitiesWithRelations(List<Long> ids);

    @Transaction
    @Query("SELECT * FROM identities WHERE fingerprint IN (:fingerprint)")
    Maybe<Identity> getIdentityByFingerprint(String fingerprint);

    @Query("SELECT * FROM identities WHERE identityID IN (:ids)")
    Maybe<List<KeylessIdentity>> getByID(List<Long> ids);

    @Query("SELECT * FROM identities WHERE givenname IN (:names)")
    Maybe<List<KeylessIdentity>> getByGivenName(String[] names);

    @Query("SELECT * FROM keys WHERE keyID IN (:ids)")
    Maybe<List<Keys>> getKeys(List<Long> ids);

    @Transaction
    @Query("SELECT * FROM identities ORDER BY RANDOM() LIMIT :count")
    Single<List<Identity>> getTopRandom(int count);

    @Query("SELECT * FROM clientapp WHERE identityFK = (" +
            "SELECT identityID FROM identities WHERE fingerprint = :fp)")
    Single<List<ClientApp>> getClientApps(String fp);

    @Query("SELECT COUNT(*) FROM identities")
    int getIdentityCount();

    @Insert
    Single<Long> insert(KeylessIdentity identity);

    @Insert
    Single<List<Long>> insertClientApps(List<ClientApp> clientApps);

    @Insert
    Completable insertClientApp(ClientApp... apps);

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

    @Delete
    Completable deleteClientApps(ClientApp... apps);
}