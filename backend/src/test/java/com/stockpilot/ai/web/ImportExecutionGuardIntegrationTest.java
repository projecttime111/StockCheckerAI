package com.stockpilot.ai.web;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.repo.*;
import com.stockpilot.ai.service.*;
import com.stockpilot.ai.exception.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class ImportExecutionGuardIntegrationTest {
    @Autowired Repositories.TenantRepository tenants;
    @Autowired Repositories.UserRepository users;
    @Autowired Repositories.MembershipRepository memberships;
    @Autowired Repositories.ImportSessionRepository sessions;
    @Autowired ImportJobRepository jobs;
    @Autowired ImportJobService service;
    @Autowired ImportExecutionGuard guard;
    @Autowired TenantWriteLockService locks;
    @Autowired PlatformTransactionManager transactionManager;
    private final List<UUID> fixtureJobs=new ArrayList<>();
    @AfterEach void clear(){ImportExecutionGuard.clear();TenantContext.clear();jobs.deleteAllById(fixtureJobs);fixtureJobs.clear();}

    @Test void roleRevokedWhileWaitingForTenantLockCannotExecute() throws Exception {
        var f=fixture();var locked=new CountDownLatch(1);var authorized=new CountDownLatch(1);var wrote=new AtomicBoolean();
        var pool=Executors.newFixedThreadPool(2);
        try{
            var owner=pool.submit(()->new TransactionTemplate(transactionManager).execute(status->{
                locks.lock(f.tenant.id);locked.countDown();await(authorized);
                var membership=memberships.findByTenantIdAndUserId(f.tenant.id,f.user.id).orElseThrow();membership.role=DomainEnums.Role.STAFF;memberships.saveAndFlush(membership);return null;}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var worker=pool.submit(()->{try{service.authorize(f.job);authorized.countDown();ImportExecutionGuard.bind(f.job.id,"original");
                new TransactionTemplate(transactionManager).execute(status->{guard.lockAndAuthorize("CLASSIFY",f.session.id);wrote.set(true);return null;});
            }finally{TenantContext.clear();ImportExecutionGuard.clear();}});
            owner.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(()->worker.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
            assertThat(wrote).isFalse();
        }finally{authorized.countDown();pool.shutdownNow();}
    }
    @Test void replacedWorkerAndMismatchedOperationOrSessionCannotExecute() {
        var f=fixture();service.authorize(f.job);ImportExecutionGuard.bind(f.job.id,"original");
        var row=jobs.findByTenantIdAndImportSessionIdAndId(f.tenant.id,f.session.id,f.job.id).orElseThrow();row.workerId="replacement";jobs.saveAndFlush(row);
        var transaction=new TransactionTemplate(transactionManager);
        assertThatThrownBy(()->transaction.execute(s->{guard.lockAndAuthorize("CLASSIFY",f.session.id);return null;})).isInstanceOf(ApiException.class);
        ImportExecutionGuard.bind(f.job.id,"replacement");
        assertThatThrownBy(()->transaction.execute(s->{guard.lockAndAuthorize("STAGE",f.session.id);return null;})).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->transaction.execute(s->{guard.lockAndAuthorize("CLASSIFY",UUID.randomUUID());return null;})).isInstanceOf(ApiException.class);
    }
    @Test void expiredLeaseCannotExecuteAndAValidClaimCan() {
        var f=fixture();service.authorize(f.job);ImportExecutionGuard.bind(f.job.id,"original");var tx=new TransactionTemplate(transactionManager);
        tx.execute(s->{guard.lockAndAuthorize("CLASSIFY",f.session.id);return null;});
        var row=jobs.findByTenantIdAndImportSessionIdAndId(f.tenant.id,f.session.id,f.job.id).orElseThrow();row.leaseUntil=Instant.now().minusSeconds(1);jobs.saveAndFlush(row);
        assertThatThrownBy(()->tx.execute(s->{guard.lockAndAuthorize("CLASSIFY",f.session.id);return null;})).isInstanceOf(ApiException.class);
    }
    @Test void anExecutingJobCannotBeReclaimedEvenWhenItsOldLeaseExpires() throws Exception {
        var f=fixture();var row=jobs.findByTenantIdAndImportSessionIdAndId(f.tenant.id,f.session.id,f.job.id).orElseThrow();
        row.leaseUntil=Instant.now().plusMillis(1200);row.createdAt=Instant.EPOCH;jobs.saveAndFlush(row);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var running=pool.submit(()->{try{service.authorize(f.job);ImportExecutionGuard.bind(f.job.id,"original");
                new TransactionTemplate(transactionManager).execute(s->{guard.lockAndAuthorize("CLASSIFY",f.session.id);locked.countDown();await(release);return null;});
            }finally{TenantContext.clear();ImportExecutionGuard.clear();}});
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();Thread.sleep(1300);
            var claimed=pool.submit(()->service.claim("replacement")).get(2,TimeUnit.SECONDS);
            assertThat(claimed==null||!claimed.id.equals(f.job.id)).isTrue();release.countDown();running.get(10,TimeUnit.SECONDS);
            var completed=jobs.findByTenantIdAndImportSessionIdAndId(f.tenant.id,f.session.id,f.job.id).orElseThrow();
            assertThat(completed.workerId).isEqualTo("original");assertThat(completed.leaseUntil).isAfter(Instant.now());
        }finally{release.countDown();pool.shutdownNow();}
    }
    private Fixture fixture(){
        var tenant=new Tenant();tenant.name="Import guard "+UUID.randomUUID();tenant.businessMode=DomainEnums.BusinessMode.HYBRID;tenants.saveAndFlush(tenant);
        var user=new UserAccount();user.email=UUID.randomUUID()+"@example.com";user.fullName="Importer";user.passwordHash="unused";users.saveAndFlush(user);
        var membership=new UserTenantMembership();membership.tenantId=tenant.id;membership.userId=user.id;membership.role=DomainEnums.Role.ADMIN;memberships.saveAndFlush(membership);
        var session=new ImportSession();session.tenantId=tenant.id;session.name="Guarded import";sessions.saveAndFlush(session);
        var job=new ImportJob();job.tenantId=tenant.id;job.importSessionId=session.id;job.requestedBy=user.id;job.requestId=UUID.randomUUID();job.requestHash="fixture";
        job.operation="CLASSIFY";job.status="RUNNING";job.workerId="original";job.leaseUntil=Instant.now().plusSeconds(120);jobs.saveAndFlush(job);fixtureJobs.add(job.id);
        return new Fixture(tenant,user,session,job);
    }
    private void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("Test synchronization timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private record Fixture(Tenant tenant,UserAccount user,ImportSession session,ImportJob job){}
}
