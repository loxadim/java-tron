package org.tron.core.witness;

import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.joda.time.DateTime;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.RandomGenerator;
import org.tron.common.utils.StringUtil;
import org.tron.common.utils.Time;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.db.AccountStore;
import org.tron.core.db.Manager;
import org.tron.core.db.WitnessStore;
import org.tron.core.exception.BadItemException;
import org.tron.core.exception.ItemNotFoundException;

@Slf4j
public class WitnessController {

  @Setter
  private Manager manager;
//  private volatile List<WitnessCapsule> wits = new ArrayList<>();

//  @Getter
//  @Setter
//  protected List<WitnessCapsule> shuffledWitnessStates;

//  private ReadWriteLock witsLock = new ReentrantReadWriteLock();
//  private Lock witsRead = witsLock.readLock();
//  private Lock witsWrite = witsLock.writeLock();

  public static WitnessController createInstance(Manager manager) {
    WitnessController instance = new WitnessController();
    instance.setManager(manager);
    return instance;
  }


  public void initWits() {
//    getWitnesses().clear();
    List<ByteString> witnessAddresses = new ArrayList<>();
    manager.getWitnessStore().getAllWitnesses().forEach(witnessCapsule -> {
      if (witnessCapsule.getIsJobs()) {
        witnessAddresses.add(witnessCapsule.getAddress());
      }
    });
    setActiveWitnesses(witnessAddresses);
    sortWitness(witnessAddresses);
    witnessAddresses.forEach(address -> {
      logger.info("initWits shuffled addresses:" + ByteArray.toHexString(address.toByteArray()));
    });
    setCurrentShuffledWitnesses(witnessAddresses);
  }
  
  public WitnessCapsule getWitnesseByAddress(ByteString address) {
    return this.manager.getWitnessStore().get(address.toByteArray());
  }

  public List<ByteString> getActiveWitnesses() {
    return this.manager.getWitnessScheduleStore().getActiveWitnesses();
  }
  
  public void setActiveWitnesses(List<ByteString> addresses) {
    this.manager.getWitnessScheduleStore().saveActiveWitnesses(addresses);
  }

//  public void addWitness(final WitnessCapsule witnessCapsule) {
//    witsWrite.lock();
//    this.wits.add(witnessCapsule);
//    witsWrite.unlock();
//  }

  public List<ByteString> getCurrentShuffledWitnesses() {
    return this.manager.getWitnessScheduleStore().getCurrentShuffledWitnesses();
  }

  public void setCurrentShuffledWitnesses(List<ByteString> addresses) {
    this.manager.getWitnessScheduleStore().saveCurrentShuffledWitnesses(addresses);
  }

  /**
   * get slot at time.
   */
  public long getSlotAtTime(long when) {
    long firstSlotTime = getSlotTime(1);
    if (when < firstSlotTime) {
      return 0;
    }
    logger
        .debug("nextFirstSlotTime:[{}],when[{}]", new DateTime(firstSlotTime), new DateTime(when));
    return (when - firstSlotTime) / Manager.LOOP_INTERVAL + 1;
  }

  public BlockCapsule getGenesisBlock() {
    return manager.getGenesisBlock();
  }

  public BlockCapsule getHead() {
    try {
      return manager.getBlockStore()
          .get(manager.getDynamicPropertiesStore().getLatestBlockHeaderHash().getBytes());
    } catch (ItemNotFoundException e) {
      return null;
    } catch (BadItemException e) {
      return null;
    }
  }

  public boolean lastHeadBlockIsMaintenance() {
    return manager.lastHeadBlockIsMaintenance();
  }

  /**
   * get absolute Slot At Time
   */
  public long getAbSlotAtTime(long when) {
    return (when - getGenesisBlock().getTimeStamp()) / Manager.LOOP_INTERVAL;
  }

  /**
   * get slot time.
   */
  public long getSlotTime(long slotNum) {
    if (slotNum == 0) {
      return Time.getCurrentMillis();
    }
    long interval = Manager.LOOP_INTERVAL;

    if (getHead().getNum() == 0) {
      return getGenesisBlock().getTimeStamp() + slotNum * interval;
    }

    if (lastHeadBlockIsMaintenance()) {
      slotNum += manager.getSkipSlotInMaintenance();
    }

    long headSlotTime = getHead().getTimeStamp();
    headSlotTime = headSlotTime
        - ((headSlotTime - getGenesisBlock().getTimeStamp()) % interval);

    return headSlotTime + interval * slotNum;
  }

  /**
   * validate witness schedule.
   */
  public boolean validateWitnessSchedule(BlockCapsule block) {

    ByteString witnessAddress = block.getInstance().getBlockHeader().getRawData()
        .getWitnessAddress();
    BlockCapsule head = getHead();
    //to deal with other condition later
    if (head.getNum() != 0 && head.getBlockId().equals(block.getParentHash())) {
      long slot = getSlotAtTime(block.getTimeStamp());
      final ByteString scheduledWitness = getScheduledWitness(slot);
      if (!scheduledWitness.equals(witnessAddress)) {
        logger.warn(
            "Witness is out of order, scheduledWitness[{}],blockWitnessAddress[{}],blockTimeStamp[{}],slot[{}]",
            ByteArray.toHexString(scheduledWitness.toByteArray()),
            ByteArray.toHexString(witnessAddress.toByteArray()), new DateTime(block.getTimeStamp()),
            slot);
        return false;
      }
    }

    logger.debug("Validate witnessSchedule successfully,scheduledWitness:{}",
        ByteArray.toHexString(witnessAddress.toByteArray()));
    return true;
  }

  /**
   * get ScheduledWitness by slot.
   */
  public ByteString getScheduledWitness(final long slot) {

    final long currentSlot = getHeadSlot() + slot;

    if (currentSlot < 0) {
      throw new RuntimeException("currentSlot should be positive.");
    }
    final List<ByteString> currentShuffledWitnesses = this.getCurrentShuffledWitnesses();
    if (CollectionUtils.isEmpty(currentShuffledWitnesses)) {
      throw new RuntimeException("ShuffledWitnesses is null.");
    }
    final int witnessIndex = (int) currentSlot % currentShuffledWitnesses.size();
    logger.debug("currentSlot:" + currentSlot
        + ", witnessIndex" + witnessIndex
        + ", currentShuffledWitnesses size:" + currentShuffledWitnesses.size());

    final ByteString scheduledWitness = currentShuffledWitnesses.get(witnessIndex);
    //logger.info("scheduled_witness:" + scheduledWitness.toStringUtf8() + ",slot:" + currentSlot);

    return scheduledWitness;
  }

  public long getHeadSlot() {
    return (getHead().getTimeStamp() - getGenesisBlock().getTimeStamp())
        / Manager.LOOP_INTERVAL;
  }

  /**
   * shuffle witnesses
   */
  public void updateWitnessSchedule() {
    if (CollectionUtils.isEmpty(getActiveWitnesses())) {
      throw new RuntimeException("Witnesses is empty");
    }

    List<ByteString> currentWitsAddress = getCurrentShuffledWitnesses();
    // TODO  what if the number of witness is not same in different slot.
    long num = manager.getDynamicPropertiesStore().getLatestBlockHeaderNumber();
    long time = manager.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp();

    if (num != 0 && num % getActiveWitnesses().size() == 0) {
      logger.info("updateWitnessSchedule number:{},HeadBlockTimeStamp:{}", num, time);
      setCurrentShuffledWitnesses(new RandomGenerator<ByteString>()
          .shuffle(getActiveWitnesses(), time));

      logger.info(
          "updateWitnessSchedule,before:{} ", getAddressStringList(currentWitsAddress)
              + ",\nafter:{} " + getAddressStringList(getCurrentShuffledWitnesses()));
    }
  }

  private Map<ByteString, Long> countVote(AccountStore accountStore) {

    final Map<ByteString, Long> countWitness = Maps.newHashMap();
    final List<AccountCapsule> accountList = accountStore.getAllAccounts();
    //logger.info("there is account List size is {}", accountList.size());
    accountList.forEach(account -> {
//      logger.info("there is account ,account address is {}",
//          account.createReadableString());

      Optional<Long> sum = account.getVotesList().stream().map(vote -> vote.getVoteCount())
          .reduce((a, b) -> a + b);
      if (sum.isPresent()) {
        if (sum.get() <= account.getShare()) {
          account.getVotesList().forEach(vote -> {
            //TODO validate witness //active_witness
            ByteString voteAddress = vote.getVoteAddress();
            long voteCount = vote.getVoteCount();
            if (countWitness.containsKey(voteAddress)) {
              countWitness.put(voteAddress, countWitness.get(voteAddress) + voteCount);
            } else {
              countWitness.put(voteAddress, voteCount);
            }
          });
        } else {
          logger.info(
              "account" + account.createReadableString() + ",share[" + account.getShare()
                  + "] > voteSum["
                  + sum.get() + "]");
        }
      }
    });
    return countWitness;
  }

  /**
   * update witness.
   */
  public void updateWitness() {
    WitnessStore witnessStore = manager.getWitnessStore();
    AccountStore accountStore = manager.getAccountStore();
    Map<ByteString, Long> countWitness = countVote(accountStore);

    //Only possible during the initialization phase
    if (countWitness.size() == 0) {
      logger.info("No vote, no change to witness.");
    } else {
      List<ByteString> currentWits = getActiveWitnesses();
      List<ByteString> newWitnessAddressList = new ArrayList<>();

      witnessStore.getAllWitnesses().forEach(witnessCapsule -> {
        witnessCapsule.setVoteCount(0);
        witnessCapsule.setIsJobs(false);
        witnessStore.put(witnessCapsule.createDbKey(), witnessCapsule);
      });

      countWitness.forEach((address, voteCount) -> {
        final WitnessCapsule witnessCapsule = witnessStore.get(StringUtil.createDbKey(address));
        if (null == witnessCapsule) {
          logger
              .warn("witnessCapsule is null.address is {}",
                  StringUtil.createReadableString(address));
          return;
        }

        ByteString witnessAddress = witnessCapsule.getInstance().getAddress();
        AccountCapsule witnessAccountCapsule = accountStore
            .get(StringUtil.createDbKey(witnessAddress));
        if (witnessAccountCapsule == null) {
          logger.warn(
              "witnessAccount[" + StringUtil.createReadableString(witnessAddress) + "] not exists");
        } else {
          if (witnessAccountCapsule.getBalance() < WitnessCapsule.MIN_BALANCE) {
            logger.warn(
                "witnessAccount[" + StringUtil.createReadableString(witnessAddress)
                    + "] has balance["
                    + witnessAccountCapsule
                    .getBalance() + "] < MIN_BALANCE[" + WitnessCapsule.MIN_BALANCE + "]");
          } else {
            witnessCapsule.setVoteCount(witnessCapsule.getVoteCount() + voteCount);
            witnessCapsule.setIsJobs(false);
            newWitnessAddressList.add(witnessAddress);
            witnessStore.put(witnessCapsule.createDbKey(), witnessCapsule);
            logger.info("address is {}  ,countVote is {}", witnessCapsule.createReadableString(),
                witnessCapsule.getVoteCount());
          }
        }
      });

      sortWitness(newWitnessAddressList);
      if (newWitnessAddressList.size() > Manager.MAX_ACTIVE_WITNESS_NUM) {
        setActiveWitnesses(newWitnessAddressList.subList(0, Manager.MAX_ACTIVE_WITNESS_NUM));
      } else {
        setActiveWitnesses(newWitnessAddressList);
      }

      getActiveWitnesses().forEach(address -> {
        WitnessCapsule witnessCapsule = getWitnesseByAddress(address);
        witnessCapsule.setIsJobs(true);
        witnessStore.put(witnessCapsule.createDbKey(), witnessCapsule);
      });

      logger.info(
          "updateWitness,before:{} ", getAddressStringList(currentWits)
              + ",\nafter:{} " + getAddressStringList(getActiveWitnesses()));
    }

  }

  public int calculateParticipationRate() {
    return manager.getDynamicPropertiesStore().calculateFilledSlotsCount();
  }
  
  private static List<String> getAddressStringList(List<ByteString> witnessAddresses) {
    return witnessAddresses.stream()
        .map(witnessAddress-> ByteArray.toHexString(witnessAddress.toByteArray()))
        .collect(Collectors.toList());
  }

  private void sortWitness(List<ByteString> list) {
    list.sort((a, b) -> {
      long aVoteCount = getWitnesseByAddress(a).getVoteCount();
      long bVoteCount = getWitnesseByAddress(b).getVoteCount();
      if (bVoteCount != aVoteCount) {
        return (int) (bVoteCount - aVoteCount);
      } else {
        return Long.compare(b.hashCode(), a.hashCode());
      }
    });
  }

}
